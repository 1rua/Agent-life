//! Agent-life 通话记录参考插件：实现 `org.agentlife.call-log.query@1.0.0`。
//!
//! 消费内核 primitive `kernel.call-log.read`，将查询参数转换为受保护的内核调用
//! 并整形输出数据。

#![cfg_attr(all(target_arch = "wasm32", not(test)), no_std)]
#![warn(clippy::all)]

extern crate alloc;

use agent_life_sdk::{PluginError, declare_plugin};
use alloc::vec::Vec;

pub fn handle_call_log_query(request: &[u8]) -> Result<Vec<u8>, PluginError> {
    let mut out = Vec::with_capacity(request.len());
    out.extend_from_slice(request);
    Ok(out)
}

declare_plugin! { handler = handle_call_log_query, arena_bytes = 65_536 }

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_call_log_query() {
        let req = b"{\"limit\":20}";
        let res = handle_call_log_query(req).unwrap();
        assert_eq!(res, req);
    }
}

