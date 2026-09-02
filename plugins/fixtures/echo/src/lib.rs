//! echo 参考插件：把宿主写入的请求字节原样回显。
//!
//! 它是 `open_android_intelligence_kernel_v1` 的最小可行实现，供 Android 侧的
//! `ChicoryPluginRuntime` 做端到端验证：模块必须能被加载（零 WASI 导入），
//! 入口必须能被链接，返回值的 (ptr, len) 必须能被解析。
//!
//! 行为契约：
//!
//! 调用协议与 `KernelAbi` 一致：宿主从线性内存的交换区
//! `memory[0 .. 64 KiB)` 写入请求，然后调用入口。
//!
//! 行为契约：
//!
//! - `open_android_intelligence_plugin_main(ptr, len)` 返回 `((rptr << 32) | rlen)`，
//!   其中 `memory[rptr .. rptr + rlen]` 与请求逐字节相同；
//! - 空请求返回 `(0, 0)`；
//! - 超过交换区上限的请求返回 `u64::MAX`（宿主侧同时还会在
//!   `ChicoryPluginRuntime` 里以 `BudgetExceeded("REQUEST")` 提前拒绝）。

#![cfg_attr(all(target_arch = "wasm32", not(test)), no_std)]
#![warn(clippy::all)]

extern crate alloc;

use open_android_intelligence_sdk::{PluginError, declare_plugin};
use alloc::vec::Vec;

/// 回显处理函数：把请求字节原样复制一份作为响应。
///
/// # Errors
///
/// 缓冲区不足时返回 [`PluginError::OutOfMemory`]。
pub fn echo(request: &[u8]) -> Result<Vec<u8>, PluginError> {
    let mut out = Vec::with_capacity(request.len());
    out.extend_from_slice(request);
    Ok(out)
}

// 生成 `open_android_intelligence_plugin_main` 导出与全局分配器。
// 缓冲区 64 KiB：请求在交换区不占它，所以 64 KiB 就是"响应最大 64 KiB"，
// 正好等于宿主侧允许的交换区上限。
declare_plugin! { handler = echo, arena_bytes = 65_536 }

#[cfg(test)]
mod tests {
    use super::*;

    /// 走一遍完整的入口逻辑，同时拿到打包后的返回值和响应内容。
    ///
    /// 宿主是 64 位进程，真实地址塞不进 u32，因此不能解引用打包出来的
    /// 指针；`dispatch_with_response` 把响应内容也带回来供断言。
    fn round_trip(request: &[u8]) -> (u64, Vec<u8>) {
        let (packed, response) = open_android_intelligence_sdk::dispatch_with_response(request, echo);
        let response = response.expect("echo 必须返回成功结果");
        (packed, response)
    }

    #[test]
    fn echoes_request_bytes_verbatim() {
        let request = b"hello open-android-intelligence";
        assert_eq!(echo(request).unwrap(), request);
        assert_eq!(round_trip(request).1, request);
    }

    #[test]
    fn echoes_every_byte_value() {
        let request: Vec<u8> = (0..=255u8).collect();
        assert_eq!(round_trip(&request).1, request);
    }

    #[test]
    fn echoes_utf8_boundaries_without_transcoding() {
        let request = "你好 🌍 open-android-intelligence".as_bytes();
        assert_eq!(round_trip(request).1, request);
    }

    #[test]
    fn empty_request_produces_empty_response() {
        assert_eq!(echo(b"").unwrap(), Vec::<u8>::new());
        // 空请求走的是"零长度不得解引用空指针"那条分支。
        let packed = open_android_intelligence_sdk::dispatch(0, 0, echo);
        assert_eq!(open_android_intelligence_sdk::unpack(packed), Some((0, 0)));
        assert_eq!(round_trip(b"").1, Vec::<u8>::new());
    }

    #[test]
    fn response_length_equals_request_length() {
        for len in [0usize, 1, 7, 255, 4096] {
            let request = alloc::vec![0xABu8; len];
            let (packed, response) = round_trip(&request);
            let (_, out_len) = open_android_intelligence_sdk::unpack(packed).unwrap();
            assert_eq!(out_len as usize, len, "响应长度必须等于请求长度");
            assert_eq!(response.len(), len);
        }
    }

    #[test]
    fn response_does_not_alias_the_request_buffer() {
        // 宿主可能在读响应前改写请求缓冲区；echo 必须复制而不是借用。
        let mut request = b"mutable".to_vec();
        let (_, response) = round_trip(&request);
        request.fill(0xFF);
        assert_eq!(response, b"mutable", "响应必须是独立副本");
    }

    #[test]
    fn packed_result_uses_pointer_high_length_low() {
        // 与宿主侧的解析约定绑定：bits 63..32 = ptr，bits 31..0 = len。
        let (packed, response) = round_trip(b"1234");
        assert_eq!(packed >> 32, u64::from(response.as_ptr() as u32));
        assert_eq!(packed & 0xFFFF_FFFF, 4);
    }

    #[test]
    fn echoes_the_largest_request_the_exchange_region_allows() {
        // 宿主最多写满 64 KiB 交换区；请求在交换区，响应在缓冲区，
        // 两者不争用同一块内存，所以满额请求也能完整回显。
        let request = alloc::vec![0x5Au8; open_android_intelligence_sdk::EXCHANGE_SIZE_BYTES];
        let (packed, response) = round_trip(&request);
        let (_, len) = open_android_intelligence_sdk::unpack(packed).unwrap();
        assert_eq!(len as usize, request.len());
        assert_eq!(response, request);
    }

    #[test]
    fn requests_beyond_the_exchange_region_are_rejected() {
        // 越界请求必须走失败哨兵，绝不能把插件的静态数据回显出去。
        let over = open_android_intelligence_sdk::EXCHANGE_SIZE_BYTES as u32 + 1;
        assert_eq!(open_android_intelligence_sdk::unpack(open_android_intelligence_sdk::dispatch(0, over, echo)), None);
        // 偏移 + 长度越过边界同样要拒绝，不能只看长度。
        assert_eq!(
            open_android_intelligence_sdk::unpack(open_android_intelligence_sdk::dispatch(
                1,
                open_android_intelligence_sdk::EXCHANGE_SIZE_BYTES as u32,
                echo
            )),
            None
        );
    }

    #[test]
    fn echo_does_not_use_the_kernel_import_module() {
        // 受保护插件的导入必须是空的；这条断言守住常量本身不被误改。
        assert_eq!(open_android_intelligence_sdk::KERNEL_ABI_MODULE, "open_android_intelligence_kernel_v1");
        assert_eq!(open_android_intelligence_sdk::ENTRYPOINT, "open_android_intelligence_plugin_main");
    }
}
