//! 检查 echo 插件的 **wasm 产物**是否符合 `agent_life_kernel_v1` 的 ABI 形状。
//!
//! 这是零依赖的手写 WASM 二进制解析器：只读取验证所需的最小信息（类型段、
//! 导入段、函数段、导出段）。不引入 `wasmparser` 之类的依赖，是为了让
//! 插件工具链保持零外部依赖、且验证逻辑本身可被人工审计。
//!
//! 断言：
//!
//! 1. 产物是合法的 wasm 模块；
//! 2. **导入数为 0**——即没有 WASI，也没有任何未知导入
//!    （`docs/contracts/device-plugin-package-v1.md` §5.1）；
//! 3. 导出 `agent_life_plugin_main`，签名 `(i32, i32) -> i64`；
//! 4. 只导出入口与 `memory`（外加链接器的两个全局），没有多余的攻击面；
//! 5. 导出线性内存 `memory`；
//! 6. **插件数据全部链接在交换区之上**——宿主往 `memory[0 .. 64 KiB)` 写
//!    请求时不会破坏插件的静态数据；
//! 7. 体积在受保护插件的合理范围内（防止误引入 std）。
//!
//! 前置：先构建 wasm 产物
//!
//! ```bash
//! cargo build --target wasm32-unknown-unknown --release --manifest-path plugins/Cargo.toml
//! cargo test --manifest-path plugins/Cargo.toml
//! ```

use std::path::{Path, PathBuf};

const WASM_MAGIC: [u8; 4] = [0x00, 0x61, 0x73, 0x6D];
const WASM_VERSION: [u8; 4] = [0x01, 0x00, 0x00, 0x00];

const VALTYPE_I32: u8 = 0x7F;
const VALTYPE_I64: u8 = 0x7E;

const SECTION_TYPE: u8 = 1;
const SECTION_IMPORT: u8 = 2;
const SECTION_FUNCTION: u8 = 3;
const SECTION_MEMORY: u8 = 5;
const SECTION_EXPORT: u8 = 7;
const SECTION_DATA: u8 = 11;

/// 宿主与插件之间的交换区大小，必须与 `KernelAbi.EXCHANGE_SIZE_BYTES` 一致。
const EXCHANGE_SIZE_BYTES: u32 = 65_536;

/// wasm-ld 固定导出的两个链接器全局，宿主可以忽略。
const LINKER_GLOBAL_EXPORTS: [&str; 2] = ["__data_end", "__heap_base"];

const EXPORTC_FUNC: u8 = 0x00;
const EXPORTC_MEMORY: u8 = 0x02;

const IMPORT_DESC_FUNC: u8 = 0x00;
const IMPORT_DESC_TABLE: u8 = 0x01;
const IMPORT_DESC_MEMORY: u8 = 0x02;
const IMPORT_DESC_GLOBAL: u8 = 0x03;

/// 产物体积上限。no_std + LTO + strip 的 echo 只有几 KB；
/// 一旦误链 std 或 WASI，体积会明显变化。
const MAX_ARTIFACT_BYTES: u64 = 65_536;

#[derive(Debug, PartialEq, Eq)]
struct FuncType {
    params: Vec<u8>,
    results: Vec<u8>,
}

#[derive(Debug)]
struct Import {
    module: String,
    name: String,
}

#[derive(Debug)]
struct Export {
    name: String,
    kind: u8,
    index: u32,
}

#[derive(Debug, Default)]
struct Module {
    types: Vec<FuncType>,
    imports: Vec<Import>,
    func_types: Vec<u32>,
    exports: Vec<Export>,
    /// 每个数据段在内存 0 中的起始偏移。
    data_offsets: Vec<u32>,
    /// `(initial_pages, maximum_pages)`，maximum 未声明时为 [`None`]。
    memories: Vec<(u32, Option<u32>)>,
}

impl Module {
    fn export(&self, name: &str) -> Option<&Export> {
        self.exports.iter().find(|e| e.name == name)
    }

    /// 由导出得到函数签名。导入为空，函数索引空间与 `func_types` 一一对应。
    fn exported_func_type(&self, export: &Export) -> Option<&FuncType> {
        if export.kind != EXPORTC_FUNC {
            return None;
        }
        let imported_funcs = 0; // 断言过导入数为 0
        let idx = export.index.checked_sub(imported_funcs)? as usize;
        let type_idx = *self.func_types.get(idx)? as usize;
        self.types.get(type_idx)
    }
}

struct Reader<'a> {
    bytes: &'a [u8],
    pos: usize,
}

type Parsed<T> = Result<T, String>;

impl<'a> Reader<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, pos: 0 }
    }

    fn done(&self) -> bool {
        self.pos >= self.bytes.len()
    }

    fn take(&mut self, n: usize) -> Parsed<&'a [u8]> {
        let end = self.pos.checked_add(n).ok_or("读取越界")?;
        let slice = self.bytes.get(self.pos..end).ok_or("读取超出模块末尾")?;
        self.pos = end;
        Ok(slice)
    }

    fn byte(&mut self) -> Parsed<u8> {
        Ok(self.take(1)?[0])
    }

    /// 无符号 LEB128。
    fn var_u32(&mut self) -> Parsed<u32> {
        let mut result: u32 = 0;
        let mut shift = 0;
        loop {
            let b = self.byte()?;
            let payload = u32::from(b & 0x7F);
            result |= payload
                .checked_shl(shift)
                .ok_or("LEB128 数值超出 u32")?;
            if b & 0x80 == 0 {
                return Ok(result);
            }
            shift += 7;
            if shift > 35 {
                return Err("LEB128 编码过长".to_string());
            }
        }
    }

    /// 有符号 LEB128，用于数据段的 `i32.const` 偏移。
    fn var_s32(&mut self) -> Parsed<u32> {
        let mut result: i64 = 0;
        let mut shift = 0;
        let mut byte;
        loop {
            byte = self.byte()?;
            result |= i64::from(byte & 0x7F) << shift;
            shift += 7;
            if byte & 0x80 == 0 {
                break;
            }
            if shift > 35 {
                return Err("LEB128 编码过长".to_string());
            }
        }
        if shift < 64 && byte & 0x40 != 0 {
            result |= -1i64 << shift;
        }
        u32::try_from(result).map_err(|_| "数据段偏移不是合法 u32".to_string())
    }

    fn name(&mut self) -> Parsed<String> {
        let len = self.var_u32()? as usize;
        let raw = self.take(len)?;
        String::from_utf8(raw.to_vec()).map_err(|_| "名称不是 UTF-8".to_string())
    }

    fn valtypes(&mut self) -> Parsed<Vec<u8>> {
        let count = self.var_u32()? as usize;
        (0..count).map(|_| self.byte()).collect()
    }

    fn limits(&mut self) -> Parsed<()> {
        let flags = self.byte()?;
        self.var_u32()?;
        if flags & 0x01 != 0 {
            self.var_u32()?;
        }
        Ok(())
    }
}

fn parse(bytes: &[u8]) -> Parsed<Module> {
    let mut reader = Reader::new(bytes);
    if reader.take(4)? != WASM_MAGIC {
        return Err("不是 wasm 模块（magic 不匹配）".to_string());
    }
    if reader.take(4)? != WASM_VERSION {
        return Err("不支持的 wasm 版本".to_string());
    }

    let mut module = Module::default();
    while !reader.done() {
        let id = reader.byte()?;
        let size = reader.var_u32()? as usize;
        let payload = reader.take(size)?;
        let mut section = Reader::new(payload);
        match id {
            SECTION_TYPE => {
                let count = section.var_u32()?;
                for _ in 0..count {
                    let form = section.byte()?;
                    if form != 0x60 {
                        return Err(format!("未知 functype 前缀 {form:#04x}"));
                    }
                    let params = section.valtypes()?;
                    let results = section.valtypes()?;
                    module.types.push(FuncType { params, results });
                }
            }
            SECTION_IMPORT => {
                let count = section.var_u32()?;
                for _ in 0..count {
                    let module_name = section.name()?;
                    let field_name = section.name()?;
                    match section.byte()? {
                        IMPORT_DESC_FUNC => {
                            section.var_u32()?;
                        }
                        IMPORT_DESC_TABLE => {
                            section.byte()?;
                            section.limits()?;
                        }
                        IMPORT_DESC_MEMORY => section.limits()?,
                        IMPORT_DESC_GLOBAL => {
                            section.byte()?;
                            section.byte()?;
                        }
                        other => return Err(format!("未知导入类型 {other:#04x}")),
                    }
                    module.imports.push(Import {
                        module: module_name,
                        name: field_name,
                    });
                }
            }
            SECTION_FUNCTION => {
                let count = section.var_u32()?;
                for _ in 0..count {
                    module.func_types.push(section.var_u32()?);
                }
            }
            SECTION_EXPORT => {
                let count = section.var_u32()?;
                for _ in 0..count {
                    let name = section.name()?;
                    let kind = section.byte()?;
                    let index = section.var_u32()?;
                    module.exports.push(Export { name, kind, index });
                }
            }
            SECTION_MEMORY => {
                let count = section.var_u32()?;
                for _ in 0..count {
                    let flags = section.byte()?;
                    let initial = section.var_u32()?;
                    let maximum = (flags & 0x01 != 0).then(|| section.var_u32()).transpose()?;
                    module.memories.push((initial, maximum));
                }
            }
            SECTION_DATA => {
                let count = section.var_u32()?;
                for _ in 0..count {
                    section.var_u32()?; // memory index
                    let opcode = section.byte()?;
                    let offset = match opcode {
                        // i32.const <sleb128>
                        0x41 => section.var_s32()?,
                        other => {
                            return Err(format!(
                                "数据段使用未知的偏移表达式 {other:#04x}，无法静态验证布局"
                            ))
                        }
                    };
                    if section.byte()? != 0x0B {
                        return Err("数据段偏移表达式缺少 end 指令".to_string());
                    }
                    let len = section.var_u32()?;
                    section.take(len as usize)?;
                    module.data_offsets.push(offset);
                }
            }
            _ => {}
        }
    }
    Ok(module)
}

fn artifact_path() -> PathBuf {
    if let Ok(explicit) = std::env::var("AGENT_LIFE_ECHO_WASM") {
        return PathBuf::from(explicit);
    }
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../target/wasm32-unknown-unknown/release/echo.wasm")
}

fn load_artifact() -> Vec<u8> {
    let path = artifact_path();
    let display = path.display().to_string();
    let bytes = std::fs::read(&path).unwrap_or_else(|err| {
        panic!(
            "读不到 wasm 产物 {display}（{err}）。\n\
             请先构建：cargo build --target wasm32-unknown-unknown --release --manifest-path plugins/Cargo.toml"
        );
    });
    let size = std::fs::metadata(&path).unwrap().len();
    assert!(
        size <= MAX_ARTIFACT_BYTES,
        "产物 {display} 体积 {size} 字节超过 {MAX_ARTIFACT_BYTES} 字节上限，\
         可能误引入了 std 或 WASI 运行时"
    );
    bytes
}

#[test]
fn artifact_is_a_valid_wasm_module() {
    let bytes = load_artifact();
    let module = parse(&bytes).expect("产物必须是合法的 wasm 模块");
    assert!(!module.exports.is_empty(), "产物必须至少导出一个符号");
}

#[test]
fn artifact_has_zero_imports() {
    let bytes = load_artifact();
    let module = parse(&bytes).unwrap();
    assert!(
        module.imports.is_empty(),
        "受保护插件必须零导入（不得有 WASI 或任何未知导入），实际导入：{:?}",
        module
            .imports
            .iter()
            .map(|i| format!("{}::{}", i.module, i.name))
            .collect::<Vec<_>>()
    );
}

#[test]
fn artifact_does_not_import_wasi() {
    let bytes = load_artifact();
    let module = parse(&bytes).unwrap();
    for import in &module.imports {
        assert_ne!(
            import.module, "wasi_snapshot_preview1",
            "禁止 WASI 导入：{:?}",
            import.name
        );
        assert_ne!(
            import.module, "wasi_unstable",
            "禁止 WASI 导入：{:?}",
            import.name
        );
    }
}

#[test]
fn artifact_exports_the_kernel_entrypoint() {
    let bytes = load_artifact();
    let module = parse(&bytes).unwrap();
    let export = module
        .export("agent_life_plugin_main")
        .expect("必须导出 agent_life_plugin_main");
    let signature = module
        .exported_func_type(export)
        .expect("agent_life_plugin_main 必须是函数导出");
    assert_eq!(
        signature,
        &FuncType {
            params: vec![VALTYPE_I32, VALTYPE_I32],
            results: vec![VALTYPE_I64],
        },
        "agent_life_plugin_main 的签名必须是 (i32, i32) -> i64"
    );
}

#[test]
fn artifact_exports_nothing_beyond_the_entrypoint_and_memory() {
    let bytes = load_artifact();
    let module = parse(&bytes).unwrap();

    let unexpected: Vec<String> = module
        .exports
        .iter()
        .map(|e| e.name.clone())
        .filter(|name| {
            name != "agent_life_plugin_main"
                && name != "memory"
                && !LINKER_GLOBAL_EXPORTS.contains(&name.as_str())
        })
        .collect();

    assert!(
        unexpected.is_empty(),
        "受保护插件不得导出入口、memory 与链接器全局之外的符号，实际多出：{unexpected:?}"
    );
}

#[test]
fn artifact_keeps_its_data_out_of_the_exchange_region() {
    let bytes = load_artifact();
    let module = parse(&bytes).unwrap();

    assert!(
        !module.data_offsets.is_empty(),
        "解析不到数据段，无法验证内存布局"
    );
    for offset in &module.data_offsets {
        assert!(
            *offset >= EXCHANGE_SIZE_BYTES,
            "插件数据段起始于 {offset}，落在宿主写入请求的交换区 [0, {EXCHANGE_SIZE_BYTES}) 内；\
             宿主写请求会破坏插件的静态数据"
        );
    }
}

#[test]
fn artifact_declares_enough_memory_for_the_exchange_region() {
    let bytes = load_artifact();
    let module = parse(&bytes).unwrap();

    assert_eq!(module.memories.len(), 1, "受保护插件必须只声明一块线性内存");
    let (initial_pages, _) = module.memories[0];

    // 交换区必须真实存在：SDK 不再用 memory.size 做运行期边界检查
    // （LTO 会把 memory.size 折叠成 0），这条静态断言是它唯一的保证。
    let initial_bytes = u64::from(initial_pages) * 65_536;
    assert!(
        initial_bytes >= u64::from(EXCHANGE_SIZE_BYTES),
        "初始内存 {initial_bytes} 字节小于交换区 {EXCHANGE_SIZE_BYTES} 字节"
    );

    // 宿主给受保护插件的默认预算是 1 MiB（16 页），超了会被直接拒绝加载。
    assert!(
        initial_pages <= 16,
        "初始内存 {initial_pages} 页超过宿主 16 页预算，模块会被 ChicoryPluginRuntime 拒绝"
    );
}

#[test]
fn artifact_does_not_export_a_start_function() {
    let bytes = load_artifact();
    let module = parse(&bytes).unwrap();
    assert!(
        module.export("_start").is_none(),
        "受保护插件不得导出 _start：实例化阶段不允许在附加预算之前执行任何代码"
    );
}

#[test]
fn artifact_exports_linear_memory() {
    let bytes = load_artifact();
    let module = parse(&bytes).unwrap();
    let memory = module.export("memory").expect("必须导出线性内存，宿主才能读写请求与响应");
    assert_eq!(memory.kind, EXPORTC_MEMORY, "memory 必须是 memory 导出");
}
