//! 固定受保护插件的线性内存布局。
//!
//! 默认布局会把 1 MiB 的栈放在地址 0 处，带来两个问题：
//!
//! 1. 宿主写入请求的交换区 `memory[0 .. 64 KiB)` 落在**栈的底部可用区**
//!    里。插件递归一深，栈就会长进交换区，宿主写请求等于改写调用栈。
//! 2. 初始内存约 18 页，超过宿主给受保护插件的内存预算。
//!
//! 这里把全局基址抬到栈之上（栈前置于数据，是 wasm-ld 的 `--stack-first`
//! 默认行为，没有 `--no-stack-first` 可关），于是：
//!
//! ```text
//! 0x00000 .. 0x10000   交换区（宿主写请求，插件不占用）
//! 0x10000 .. 0x40000   对齐间隙
//! 0x40000 .. 0x80000   栈（256 KiB，向下生长，底在交换区之上）
//! 0x80000 ..           数据段与响应缓冲区
//! ```
//!
//! `--global-base` 必须不小于 `-zstack-size`，所以全局基址取两者的较大值。
//! 初始内存约 9 页，留在宿主的内存预算之内。
//!
//! `tests/wasm_abi.rs` 对这段布局做静态断言：数据段必须在交换区之上，
//! 初始页数必须在宿主预算内。
//!
//! 新建插件时复制本文件：Cargo 不会把依赖的 `rustc-link-arg` 传播到最终
//! 的 cdylib 链接，所以布局约束只能落在插件自己的构建脚本里。

/// 交换区大小，必须与 `KernelAbi.EXCHANGE_SIZE_BYTES` 和 SDK 的
/// `EXCHANGE_SIZE_BYTES` 保持一致。
const EXCHANGE_SIZE_BYTES: u32 = 65_536;

/// 受保护插件的栈预算。插件不递归，特权操作都在内核侧完成。
const STACK_SIZE_BYTES: u32 = 262_144;

fn main() {
    // 只对 wasm 目标施加布局约束；宿主目标（单元测试）保持工具链默认。
    let target = std::env::var("TARGET").unwrap_or_default();
    if !target.starts_with("wasm32") {
        return;
    }

    // wasm-ld 的 `--stack-first` 要求全局基址不小于栈大小，否则链接失败。
    let global_base = EXCHANGE_SIZE_BYTES.max(STACK_SIZE_BYTES);

    println!("cargo:rustc-link-arg=--global-base={global_base}");
    println!("cargo:rustc-link-arg=-zstack-size={STACK_SIZE_BYTES}");
    println!("cargo:rerun-if-changed=build.rs");
}
