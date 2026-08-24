---
status: accepted
date: 2026-08-22
---

# 绑定 companion 作者并由宿主代理 Gateway 访问

第三方 companion APK 无需与 Android 宿主同签名，但其 package name 和 Android 签名证书必须由对应插件包的作者签名清单绑定，并由宿主读取真实安装证书核验。Companion 只持有完成自身 Android 能力所需的权限，通过平台内核控制的进程间接口接收类型化请求，不持有 Gateway 配对凭据，也不直接使用 Gateway 通道。每次调用使用绑定双方身份、Gateway 配对、单一操作、参数摘要、授权与插件版本、期限和一次性随机数的短期操作令牌；崩溃、重启、升级或撤权使旧令牌失效。手机保存能力默认提供者，每个 Gateway 配对可以显式覆盖；更换提供者必须重新授权，旧授权不得自动转移。
