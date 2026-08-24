# Agent-life 领域语境

Agent-life 让 Android 设备连接到用户拥有的 Agent Gateway，同时确保手机端始终是本机数据与设备操作的最终授权者。产品由极简 Android 宿主、可安装设备插件和 Agent 宿主内的 Gateway 适配器组成。

## Android 端

**Android 宿主（Android Host）**:
始终安装的 Android 产品入口，向用户提供 Gateway 连接与对话，以及由用户明确选择的附件上传；它同时承载手机端的本地信任边界。
_Avoid_: App 本体、能力合集、Device Bridge 客户端

**平台内核（Platform Kernel）**:
Android 宿主中不可卸载的本地权威，管理设备身份、插件生命周期、权限授予、隔离、审计和紧急停用；在受保护模式下，它是插件能力的最终裁决者，但它本身不提供短信、通知、控制等设备业务能力。
_Avoid_: 核心插件、超级插件

**设备插件（Device Plugin）**:
由用户安装、启用或停用的扩展单元，为手机增加一组边界明确的数据、操作或连接能力；任何人都可以制作和分发设备插件，受保护模式中的插件不能自行扩大用户授予的权限。
_Avoid_: 内置功能、Gradle 模块、无限权限插件

**受保护模式（Protected Mode）**:
Android 宿主的默认安全状态，仅运行能够被平台内核强制限制的 App 内插件；平台内核对插件的身份、能力和数据访问保持最终控制。
_Avoid_: 普通模式、官方插件模式

**开发者信任模式（Developer Trust Mode）**:
用户明确开启后允许同进程原生插件运行的高级状态；在此模式中，原生插件被视为平台内核的一部分可信代码，宿主不再承诺能隔离其对现有权限和数据的访问。
_Avoid_: 开发者选项、调试模式、沙箱模式

**原生插件（Native Plugin）**:
在 Android 宿主进程中执行 Kotlin、Java、DEX 或本机代码的设备插件；它与宿主共享 Android 安全身份，只能在开发者信任模式中安装和运行。
_Avoid_: 沙箱插件、受限插件

**受保护插件（Protected Plugin）**:
在受保护模式中运行的 WASM 设备插件，其配置与界面由宿主根据声明渲染；它只能调用平台内核明确导出的能力，并受时间、内存、存储、网络和并发限额约束。
_Avoid_: 原生插件、WebView 插件、无限资源插件

**插件作者密钥（Plugin Author Key）**:
插件作者自行持有、用于标识插件来源和连续更新关系的签名身份；用户首次信任该密钥，后续更换密钥必须重新确认。
_Avoid_: 官方批准、代码安全证明、插件 ID

**插件身份（Plugin Identity）**:
命名空间插件 ID 与插件作者密钥共同组成的稳定身份；同名但作者密钥不同的插件彼此独立，不共享更新、数据或授权。
_Avoid_: 插件 ID、版本号、文件摘要

**插件能力（Plugin Capability）**:
设备插件以作者命名空间定义并通过版本化结构描述的一项功能；它可以自由扩展，但不能重新定义平台内核拥有的安全原语。
_Avoid_: 全局短名称、Android 权限、自然语言工具描述

**内核安全原语（Kernel Security Primitive）**:
由平台内核固定定义并评定风险的敏感基础操作，例如附件读取、网络访问、后台任务、短信、屏幕或设备控制；插件只能请求使用，不能修改其含义或风险等级。
_Avoid_: 插件自定义能力、通配权限、可覆盖风险标签

**宿主能力包络（Host Capability Envelope）**:
当前 Android 宿主版本通过 Manifest 与平台内核实际提供的权限、系统组件和内核安全原语集合；运行时插件可以自由组合包络内能力，但不能凭空增加包络外的 Android 能力。
_Avoid_: 无限 Android API、插件权限清单、未来能力承诺

**Companion 插件（Companion Plugin）**:
以独立 Android package 安装、为宿主能力包络之外的权限、系统组件或隔离需求提供能力的设备插件；它通过平台内核控制的进程间接口参与 Agent-life，而不是直接向 Gateway 暴露权限。
_Avoid_: 第二宿主、独立 Agent 客户端、权限旁路

**Companion 作者绑定（Companion Author Binding）**:
插件包作者签名对 companion package name 与 Android 签名证书的明确绑定；宿主以真实安装证书核对该绑定，而不把 package name 当作作者身份。
_Avoid_: 同名 package、宿主同签名要求、未验证 APK

**Companion 操作令牌（Companion Operation Token）**:
平台内核为一次具体 companion 操作签发的短期、单用途授权，绑定调用双方身份、Gateway 配对、插件与能力版本、参数摘要、期限和一次性随机数。
_Avoid_: 长期 IPC 密钥、通用令牌、package name 授权

**插件包（Plugin Package）**:
包含插件清单、运行载荷、能力请求、资源预算、兼容范围、文件摘要和作者签名的确定性安装产物；源码仓库是开发来源，不是可直接执行的正式插件包。
_Avoid_: Git 仓库、未固定下载目录、裸 WASM

**能力依赖（Capability Dependency）**:
插件对某项版本化能力的需求，而不是对另一个插件内部代码或文件的依赖；能力提供者缺失时，必需依赖阻止启用，可选依赖触发明确降级。
_Avoid_: 插件代码导入、隐式依赖、授权继承

**能力提供者选择（Capability Provider Selection）**:
平台内核为一项能力选择实际设备插件的路由决定；手机保存默认提供者，每个 Gateway 配对可以显式覆盖，切换提供者必须重新授权。
_Avoid_: 同时调用全部插件、自动继承授权、全局唯一实现

**受控网络访问（Mediated Network Access）**:
受保护插件通过平台内核代理发起的 HTTPS 访问，受声明域名、方法、用途和用户授权约束；它不向插件提供原始 socket、任意 DNS 或忽略证书错误的能力。
_Avoid_: 直接联网、原始 socket、Gateway 配对通道

**插件私有存储（Plugin Private Store）**:
由平台内核提供并执行配额的加密数据空间；插件代码与静态资源可以手机级共享，但可变数据以插件身份、Gateway 账号和 Android 安装实例的组合隔离，插件不能读取其他组合的空间，卸载时默认删除。
_Avoid_: 共享账号数据目录、任意文件路径、跨作者同名数据

**本地账号资料（Local Account Profile）**:
Android 为曾登录的 Gateway 账号保存的非秘密显示名、Gateway 地址、证书信任和登录配置；它允许用户快速切换账号，但不等于活动会话、账号密码或设备授权。
_Avoid_: 保存的密码、登录会话、Gateway 账号副本

**账号刷新凭据（Account Refresh Credential）**:
Gateway 在首次密码验证后签发给一个账号与一个 Android 安装实例的可轮换、可撤销秘密，用于应用重启和账号切换时自动恢复登录；保存它不自动授予后台同步或设备插件能力。
_Avoid_: 保存的账号密码、永久令牌、配对私钥

**资源预算（Resource Budget）**:
插件为执行时间、内存、存储、网络、并发和后台频率声明的需求范围；平台内核拥有硬上限，用户可以在上限内批准或收紧。
_Avoid_: 作者配额、无限资源、性能建议

**插件安装（Plugin Installation）**:
让某个设备插件的内容出现在手机上的动作；安装不代表插件已经启用，也不代表任何 Gateway 已获得使用权限。
_Avoid_: 启用、授权、授予 Android 权限

**插件启用（Plugin Enablement）**:
用户允许一个已安装设备插件在 Android 宿主中工作的手机级状态；它不自动授予任何 Gateway 访问该插件的能力。
_Avoid_: 安装、配对授权

**插件运行状态（Plugin Operational State）**:
插件从发现、下载、验证、安装、停用到启用的明确生命周期状态，以及等待批准、缺少依赖、不兼容、隔离、回滚和卸载等非运行状态。
_Avoid_: 已安装即运行、布尔启用标志、尽力加载

**连接插件（Transport Plugin）**:
为 Android 宿主增加一种到达 Gateway 的网络路径的设备插件；它只改变连通方式，不替代设备身份、配对或应用层授权。
_Avoid_: 授权插件、身份提供方、默认 Tailscale

**Tailscale Companion 插件（Tailscale Companion Plugin）**:
以独立 Companion APK 承载 tsnet 原生网络栈的可选连接插件；它默认不安装、不启用，只为同一个 HTTPS Gateway 提供网络路径，不持有 Gateway 凭据。
_Avoid_: 默认传输、App 内核网络、TLS 信任替代品

**官方参考插件（First-party Reference Plugin）**:
由 Agent-life 项目签名发布、用于证明开放插件契约可实现的普通设备插件；它不享有隐藏能力、默认启用或绕过授权的特权。
_Avoid_: 内置功能、系统插件、强制安装

## Agent 端

**Gateway**:
属于单个 Gateway 账号的逻辑 Agent-life 通信入口；一台手机可以保存多个 Gateway，一个 Gateway 可以配对多台手机，而同一适配器部署可以承载多个彼此隔离的 Gateway。
_Avoid_: 物理插件进程、共享租户、独立 Device Bridge

**Gateway 部署（Gateway Deployment）**:
安装在一个 Hermes 或 OpenClaw Agent 宿主中的物理 Gateway 适配器运行单元；它可以承载多个账号，但不允许账号之间共享逻辑 Gateway 状态。
_Avoid_: 逻辑 Gateway、用户账号、中央 Bridge

**Gateway 账号（Gateway Account）**:
由 Gateway 部署所有者创建或邀请、用于标识一个 Agent 用户的稳定逻辑身份；每个账号拥有一个独立逻辑 Gateway，用户名只是可变显示与登录名，不作为隔离主键。
_Avoid_: 用户名、设备身份、公开自注册用户

**Gateway Core**:
Hermes 与 OpenClaw 适配器共同遵守的 Gateway 行为、数据边界与一致性测试，包括配对、设备协议、消息可靠性、附件生命周期和审计语义；它不表示两端必须运行同一个二进制文件。
_Avoid_: 通用宿主插件、共享二进制、Docker Bridge

**Gateway 适配器（Gateway Adapter）**:
安装到特定 Agent 宿主并把 Agent-life 注册为消息入口的宿主插件；Hermes 与 OpenClaw 分别拥有自己的适配器，并共享 Gateway Core，一个适配器部署可以承载多个 Gateway 账号。
_Avoid_: 单一跨宿主二进制、Bridge 客户端

**旧 Bridge（Legacy Bridge）**:
被 Gateway Protocol v2 与宿主内 Gateway 适配器取代的独立 Docker/systemd Device Bridge；迁移期间仅作为既有安全语义、存储逻辑、测试和 tsnet 证据的冻结来源，不再获得新功能。
_Avoid_: Gateway Core、兼容层、继续部署的生产服务

**Gateway 所有者（Gateway Owner）**:
拥有并控制一个逻辑 Gateway 及其全部配对设备、数据、配置和审计记录的唯一 Gateway 账号。
_Avoid_: 部署操作员、共享管理员、用户名

**账号登录会话（Account Login Session）**:
Android 通过账号密码、账号刷新凭据或账号绑定的设备密钥建立的短期 Gateway 会话；会话证明当前账号与设备上下文，但不会越过配对授权或后台同步授权。
_Avoid_: 保存的密码、永久登录、全局设备授权

**配对（Pairing）**:
一个 Android 安装实例与一个账号所属逻辑 Gateway 之间独立建立的信任关系；所有连接方式先确定账号，每个配对拥有彼此隔离的身份、授权、消息、附件和审计状态。
_Avoid_: 登录、网络连通、全局设备注册

**账号设备会话（Account Device Session）**:
一个 Android 安装实例登录某个 Gateway 账号后建立的独立设备上下文；即使多个设备使用同一账号，它们也不共享设备 ID、插件授权或审计身份。
_Avoid_: 账号会话、共享设备、IP 身份

**退出登录（Logout）**:
结束一个本地账号的登录会话并撤销其账号刷新凭据，同时保留本地账号资料以便日后重新登录。
_Avoid_: 移除本地账号、解除配对、删除 Gateway 账号

**移除本地账号（Local Account Removal）**:
退出登录并从当前手机移除该账号的本地资料、插件账号数据和缓存；它不删除服务端账号，也不代表其他设备解除配对。
_Avoid_: 退出登录、解除配对、删除 Gateway 账号

**解除配对（Unpairing）**:
终止一个 Android 安装实例与一个 Gateway 之间信任关系的动作；它立即撤销访问并销毁该配对的密钥、待处理消息和未保存附件。
_Avoid_: 断开连接、停用 Gateway、退出对话

**删除 Gateway 账号（Gateway Account Deletion）**:
经明确确认后删除一个逻辑 Gateway 及其全部设备、刷新凭据、配对、队列、附件和账号状态的服务端动作；它不同于手机上的退出或本地移除。
_Avoid_: 退出登录、移除本地账号、解除单台设备配对

**账号凭据重置（Account Credential Reset）**:
由 Gateway 部署操作员在本机发起的密码重置或一次性邀请流程；重置撤销该账号的刷新凭据，既有配对密钥可按是否疑似泄露选择保留或全部撤销。
_Avoid_: 公开邮件找回、匿名自助恢复、静默保留全部会话

**配对授权（Pairing Grant）**:
用户允许某个 Gateway 通过一个已启用设备插件使用的能力范围；新配对默认没有设备插件授权，且任何配对授权都不能超过手机级限制。
_Avoid_: 插件启用、全局授权、继承授权

**活动 Gateway（Active Gateway）**:
用户当前选择作为对话目标的 Gateway；保存或配对一个 Gateway 不会使它自动成为活动 Gateway。
_Avoid_: 默认 Gateway、唯一 Gateway

**对话线程（Conversation Thread）**:
只属于一个 Gateway 的连续对话边界；Android 的本地线程标识可绑定一个宿主会话，但不同 Gateway 的线程不得合并或继承彼此上下文。
_Avoid_: 全局聊天、跨 Gateway 会话、配对

**后台同步授权（Background Sync Grant）**:
用户针对一个 Gateway 与一个设备插件明确开启的后台数据交换许可；保存 Gateway、安装插件或启用插件都不会自动产生该许可。
_Avoid_: 配对、默认同步、全局同步

**Gateway 协议状态（Gateway Protocol State）**:
Gateway 为维持设备关系与可靠通信而保存的状态，包括配对、消息去重、待处理消息、附件生命周期、操作回执和审计记录。
_Avoid_: 对话历史、Agent 记忆、Bridge 数据库

**设备正文暂存（Device Content Staging）**:
Gateway 为排队并向 Agent 宿主交付通知、短信、联系人或传感器等正文而进行的短期保存；宿主确认接收或期限到期后删除，不形成长期设备内容仓库。
_Avoid_: 设备数据湖、长期索引、Agent 记忆

**Gateway 主密钥（Gateway Master Key）**:
用于保护 Gateway 敏感数据库字段与附件暂存内容、但不与被保护数据一同导出的本地秘密；优先由宿主秘密存储提供，否则由用户显式配置受限密钥来源。
_Avoid_: TLS 私钥、用户密码、数据库内密钥

**附件暂存区（Attachment Staging Store）**:
Gateway 在附件完整校验并交付 Agent 宿主前使用的加密临时存储；宿主确认接收、上传失败、配对解除或期限到期都会触发删除。
_Avoid_: Agent 长期附件库、公开上传目录、永久文件仓库

**Gateway 迁移模式（Gateway Migration Mode）**:
Gateway 暂停对外入口、以事务迁移协议状态并验证后再恢复服务的维护状态；失败时恢复旧版本，不允许半迁移数据库继续接收设备请求。
_Avoid_: 在线就地修改、可携带备份、尽力迁移

**Agent 工作状态（Agent Working State）**:
Hermes 或 OpenClaw 拥有的对话、任务、模型运行和长期记忆状态；它不作为设备配对、防重放或设备审计的权威来源。
_Avoid_: Gateway 协议状态、设备授权状态

**Gateway Protocol v2**:
Android 宿主与 Agent 内 Gateway 之间的新一代版本化设备协议；它复用既有安全语义，但不兼容以独立 Device Bridge 为中心的旧网络接口。
_Avoid_: Bridge Protocol v1、旧 Bridge 兼容层

**协议协商（Protocol Negotiation）**:
Android 与 Gateway 在连接开始时确定共同支持的协议、认证、消息、附件、事件游标和能力版本；主版本或未知高风险语义不兼容时拒绝连接。
_Avoid_: 尽力兼容、请求内扩权、虚报功能

**私有证书信任（Private Certificate Trust）**:
用户首次确认私有 CA 或自签名 Gateway 的证书或公钥指纹后建立的固定 TLS 信任；公钥变化需要轮换证明或重新确认。
_Avoid_: 忽略证书错误、Tailscale 免验证、首次静默信任

**不安全开发连接（Insecure Development Connection）**:
仅用于本地或明确测试地址、真正忽略 TLS 身份错误的受限连接；它持续显示警告，并禁止密码、附件、后台同步、设备插件和敏感数据。
_Avoid_: 私有证书信任、生产 HTTPS、临时忽略错误

**Gateway 事件流（Gateway Event Stream）**:
Gateway 通过 HTTPS 持续发送流式回复和设备事件的有序通道；Android 使用事件游标从断点恢复，而不是假定连接永久在线。
_Avoid_: 永久在线、WebSocket 权威通道、无界重放

**Gateway 附件限制（Gateway Attachment Limit）**:
每个 Gateway 在配对配置中声明的有限单文件、单消息、媒体类型、超时和临时保留约束；Agent-life 不设统一业务大小上限，但 Android 始终可以因本机资源与用户策略拒绝上传。
_Avoid_: 无限上传、统一产品上限、远程资源命令

**可迁移 Gateway 备份（Portable Gateway Backup）**:
用户导出的 Gateway 配置、插件清单和非敏感数据集合；它不包含活动配对私钥、未执行命令或未确认队列，恢复后设备必须重新配对。
_Avoid_: 数据库快照、Gateway 身份克隆、活动授权备份

**重新配对迁移（Re-pair Migration）**:
从旧 Bridge 迁入 Gateway Protocol v2 时只导入非敏感配置和插件清单，并为每台设备重新建立身份与授权的迁移方式；旧配对密钥、队列和数据库身份不进入新系统。
_Avoid_: 身份克隆、Bridge v1 兼容、静默授权继承

**账号隔离目录（Account Isolation Directory）**:
一个 Gateway 账号专属的 SQLite 数据库、主密钥来源、附件暂存、队列和审计边界；适配器只在账号间共享代码，不共享协议状态或密钥。
_Avoid_: 共享数据库行过滤、文件名前缀隔离、部署目录

**Gateway 暴露方式（Gateway Exposure Mode）**:
Gateway Protocol v2 获得 HTTPS 入口的部署选择，包括宿主已有路由、本机监听配合反向代理，或用户显式配置证书的直接监听；三者不得改变设备协议与认证语义。
_Avoid_: 独立 Bridge、默认公网监听、协议变体

**宿主兼容范围（Host Compatibility Range）**:
Gateway 适配器经过测试并声明可运行的 Hermes 或 OpenClaw 宿主与插件 API 版本区间；超出区间时 Gateway 保持数据只读并拒绝对外服务。
_Avoid_: 尽力兼容、仅最低版本、忽略警告启动

**协议功能协商（Protocol Feature Negotiation）**:
Android 与 Gateway 在会话开始时固定双方共同支持的协议、认证、消息、附件、事件游标和能力版本；主版本或未知高风险语义不兼容时拒绝连接。
_Avoid_: 运行时猜测、单方能力声明、静默降级

**插件生命周期状态（Plugin Lifecycle State）**:
插件从发现、下载、验证、安装、启用到隔离、等待授权、回滚或卸载的明确状态；代码存在、允许运行和 Gateway 获得授权始终是三个不同事实。
_Avoid_: 已安装即授权、半启用、隐式恢复

**身份轮换（Identity Rotation）**:
Gateway、设备、插件作者、Companion 或 TLS 身份在保持可验证连续性的前提下更换密钥的过程；疑似泄露触发立即撤销与重新建立信任，而不是平滑轮换。
_Avoid_: 覆盖密钥、静默换证、泄露恢复

**待处理设备请求（Pending Device Request）**:
手机离线时由 Gateway 暂存、等待手机重新验证后处理的有期限请求；高权限临时会话不得成为待处理请求。
_Avoid_: 离线命令执行、永久任务、授权快照

**最小审计记录（Minimal Audit Record）**:
不含消息、短信或附件正文，仅说明主体、时间、动作和结果的审计条目；默认保留三十天，用户可以立即清除。
_Avoid_: 内容副本、Agent 记忆、永久日志

**Android 权威审计（Android Authoritative Audit）**:
由手机记录并作为本机插件执行、权限裁决、用户确认和 Companion 调用事实来源的最小审计；它与 Gateway 审计使用关联 ID 对应，但不上传到中央遥测服务。
_Avoid_: Gateway 请求日志、中央遥测、消息正文

**Gateway 权威审计（Gateway Authoritative Audit）**:
由每个逻辑 Gateway 记录并作为账号认证、设备请求、协议投递和服务端管理动作事实来源的最小审计；它与 Android 审计相互关联但不能替代本机事实。
_Avoid_: Android 权限日志、中央日志仓库、Agent 对话历史

**可见核心界面（Visible Core UI）**:
Android 主导航中固定存在的账号与 Gateway、对话和附件界面；插件与安全管理不进入主导航，而位于平台设置中。
_Avoid_: 插件门户首页、能力合集、动态顶级导航

**平台管理界面（Platform Management UI）**:
Android 设置中用于管理插件、权限、安全、审计和开发者信任模式的内核界面；插件只能通过声明式设置或卡片扩展该区域。
_Avoid_: 插件自绘顶级页面、WebView 管理台、可移除安全设置

**Gateway 管理入口（Gateway Management Surface）**:
Hermes 或 OpenClaw 宿主界面与具备等价能力的本地命令行入口，用于账号、邀请、证书、备份、插件状态和审计管理；敏感操作仍要求安全的本机确认。
_Avoid_: 仅宿主界面、仅命令行、远程无确认管理

**密钥轮换证明（Key Rotation Proof）**:
由旧信任材料对新密钥或证书作出的连续性声明；它减少重新建立身份的歧义，但敏感作者或证书变化仍可要求用户确认。
_Avoid_: 静默换钥、泄露后平滑轮换、仅配置更新

**Gateway 临时正文（Gateway Transient Content）**:
为排队、传输和交付 Agent 宿主而短暂存在于 Gateway 的设备或附件正文；宿主确认接收或期限到期后删除，不进入 Gateway 长期检索、记忆或索引。
_Avoid_: 同步数据仓库、Agent 长期内容、Bridge 副本
