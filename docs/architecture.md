# DSH Mobile Hosts — 架构设计

> 目标：在 Android 手机上复刻桌面插件 `dsh-remote-hosts` 的核心逻辑——读取 SSH 主机列表、
> 保存每台主机的 DSH 凭证、把远端的 `dsh web` 经 SSH 隧道暴露到手机本机回环端口，然后
> **在 App 内置的系统 WebView 里打开这个"代理网址"**（外部浏览器是可选路径），而不是打开一个本地 DSH 应用标签页。
>
> v0.2.0 新增：**App 内 SSH 密钥管理**（查看 / 生成 / 导入密钥对，主机只引用 keyId），
> 不再需要为每台 host 粘贴一份私钥。详见 docs/app-spec.md §8。
>
> v0.2.5 新增：**全局设置页**（打开方式与内置页面横栏收起，均存非敏感布尔值）与
> **内置 WebView 顶部横栏收起**；每主机的 `appendToken` 开关及字段删除。

## 1. 为什么是"单 APK、纯原生、无后端"

| 方案 | 结论 | 原因 |
|---|---|---|
| **A. 原生 Android 单 APK（选定）** | ✅ | SSH 隧道需要常驻进程 + 前台服务；浏览器 / 通知 / 加密存储都是平台能力；无需服务器 |
| B. WebView/PWA + 远程中继服务 | ❌ | 手机上无私钥驻留、无系统 ssh 客户端；中继服务要把凭证放到第三方，安全与可用性都更差 |
| C. Termux + 脚本 | ❌ | 依赖用户手动装 Termux、后台保活脆弱、体验不像 app |
| D. 自带 Node/dsh 运行时 | ❌ | 手机上并没有 dsh 应用；本任务也不需要它——远端已经在跑 `dsh web` |

所以前后端都在同一个 APK 内：

```
┌──────────────────────── Android 手机 ────────────────────────┐
│  UI 进程（Activity）                                          │
│   MainActivity / EditHostActivity / ImportConfigActivity      │
│   SshKeysActivity（v0.2.0：密钥管理）                          │
│   SettingsActivity（v0.2.5：全局开关，只存布尔值）              │
│      │  startForegroundService(ACTION_OPEN alias)             │
│      ▼                                                        │
│  TunnelService（前台服务，dataSync）                          │
│   ├─ HostStore（AES-GCM + Android Keystore 加密落盘）          │
│   │    ├─ hosts[]（keyId 引用；无 appendToken）                 │
│   │    ├─ sshKeys[]（v0.2.0：App 自管密钥对，私钥/口令在密文内）│
│   │    └─ trustedHostKeys[]（TOFU）                            │
│   ├─ SshKeyManager（v0.2.0：生成/导入/指纹，纯 Java + JSch）    │
│   ├─ SshTunnelEngine（JSch，纯 Java）                         │
│   │    ├─ 直连 host:port                                      │
│   │    ├─ 经 HostStore.findSshKey(keyId) 解析密钥材料          │
│   │    └─ ProxyJump：先连跳板，再经跳板建第二条会话            │
│   └─ HostKeyTrust（TOFU，指纹存本地）                          │
│      │  EVENT_OPEN{localPort}                                 │
│      ▼                                                        │
│  v0.2.5 全局开关（SettingsStore.isBrowserMode，默认 false）      │
│   A) true：复制 token URL 到剪贴板 + 打开浏览器首页（用户粘贴） │
│   B) false：内置 WebViewActivity（App 代做登录；横栏可收起）    │
└───────────────────────────────────────────────────────────────┘
        │ SSH (libssh 等价实现：JSch，纯 Java，自带密钥协商)
        ▼
  远端 sshd ──► 远端 127.0.0.1:<remotePort> (dsh web)
```

关键点：

1. **隧道在手机侧**：`-L 127.0.0.1:<localPort>:127.0.0.1:<remotePort>` 的等价物由 JSch 的
   `Session.setPortForwardingL("127.0.0.1", localPort, "127.0.0.1", remotePort)` 完成。
   远端不需要任何改动，也不需要装插件。
2. **认证模型不变（服务端）**：远端 `dsh web` 的 `?token=…` 登录握手仍是 `GET /?token=` →
   303 + Set-Cookie，隧道只是 TCP 透传。v0.2.5 起两种客户端交接方式由全局设置选择：
   * A（`isBrowserMode=true`）：App 把 token URL 原封不动交给用户粘贴进外部浏览器——token 短暂出现在
     地址栏/历史（与桌面端 P1 相同暴露面）；
   * B（`isBrowserMode=false`，默认）：App 在**内置 WebView** 里自己完成 token 交换，把远端
     `dsh-auth-*` cookie 注入 WebView 的 CookieManager，再加载不含 token 的
     `http://127.0.0.1:<端口>/`；token 不出现在任何 URL/Intent 中。
   * B 存在的原因：Android 浏览器会拦截"外部 App 发起 + 303 redirect"导航的 `SameSite=Strict`
     cookie（Chrome/Firefox/Edge 均如此），直接 `ACTION_VIEW` token URL 会得到 401；App 内置
     WebView 自己注入 cookie 可绕开该浏览器策略。
3. **前置服务保活**：隧道需要跨 Activity 存活，用前台服务（`dataSync`）持有 Session；
   通知栏提供"全部断开"。
4. **凭证永不进日志**：token 只出现在 `CredentialUrl.browserUrlFor()` 返回值、外部浏览器模式的剪贴板、
   内置 WebView 的内部登录请求 URL 中；不进日志/通知/Intent extras。

## 2. 组件与职责

| 组件 | 职责 | 纯 Java? |
|---|---|---|
| `core/HostConfig` | 一台主机的完整模型（SSH 连接 + 认证 + DSH 凭证 + keyId 引用；v0.2.5 已无 `appendToken`） | ✅ |
| `core/CredentialUrl` | `?token=` URL 的解析、掩码、浏览器 URL（`browserUrlFor`）与干净 URL（`plainUrlFor`）构造（唯一 token 出口） | ✅ |
| `core/SshConfigParser` | OpenSSH `config` 语义子集（Host 块、通配/取反、first-match-wins、Include、ProxyJump、IdentityFile） | ✅ |
| `core/HostsCodec` | 主机列表 + 密钥库 + 信任表 ⇄ JSON（`org.json`），与加密层解耦，便于 JVM 测试 | ✅ |
| `core/HostsMigration` | 旧版内联私钥 → 密钥库条目的迁移（幂等、按内容去重） | ✅ |
| `core/SshKeyRecord` | 一对 App 管理密钥的模型（公私钥、指纹、来源） | ✅ |
| `core/SshKeyManager` | 用 JSch 生成/导入密钥对、计算 SHA256 指纹 | ✅ |
| `core/SecretBox` | Android Keystore AES-256-GCM 加解密；密钥不可导出 | ❌ Android |
| `core/HostStore` | 加密落盘、内存缓存、主机/密钥/信任表增删改查 | ❌ Android |
| `core/SettingsStore`（v0.2.5） | 两个非敏感布尔值的全局设置（SharedPreferences `settings`）：外部浏览器模式、横栏收起；**不存任何凭证** | ❌ Android |
| `core/SshTunnelEngine` | JSch 连接、密钥解析（KeyResolver）、ProxyJump、本地端口转发、就绪探测、保活、错误分类 | ✅ |
| `core/TunnelManager` | 多主机隧道表、共享启动、关闭、状态回调 | ✅ |
| `core/TunnelService` | 前台服务、Intent 协议、广播状态、通知；token 预校验仅在 `SettingsStore.isBrowserMode()` 时执行 | ❌ Android |
| `core/ErrorKind` | 统一错误分类（对齐桌面插件的 `bad-request/unknown-host/needs-credentials/unreachable/internal`，另加 `host-key-changed`） | ✅ |
| `ui/*` | 列表 / 编辑 / 导入 / 密钥管理 / **设置** / 权限申请 / 错误提示 | ❌ Android |
| `ui/SettingsActivity`（v0.2.5） | 全局设置页（`activity_settings.xml`）：两个开关 + `?` 帮助 + 开启外部浏览器时的一次性告警 | ❌ Android |
| `ui/WebViewActivity`（v0.2.4，v0.2.5 扩展） | 系统 WebView 全自动打开：App 经隧道完成 token 交换 → `CookieManager` 注入 `dsh-auth-*` cookie → 加载不含 token 的 `http://127.0.0.1:<端口>/`；v0.2.5 顶栏可收起（56→32dp） | ❌ Android |

### 2.1 打开方式（v0.2.5：全局开关，取代每主机 `appendToken`）

| `SettingsStore.isBrowserMode` | 交付方式 |
|---|---|
| `true`（设置页「通过外部浏览器连接」开启） | 把 `http://127.0.0.1:<隧道端口>/?token=…` 原封不动复制到敏感剪贴板，并打开默认浏览器首页，由用户粘贴到地址栏打开（Android 浏览器会拦截"外部 App 发起 + 303 redirect"的 SameSite=Strict cookie；手动粘贴属用户导航可正常登录）。隧道内先做 token 预校验 |
| `false`（默认，推荐） | App 内置 `WebViewActivity` 全自动打开：App 自己完成 token 交换并注入 cookie，URL 不含 token，不经过外部浏览器；不做隧道内预校验（由 WebView 自己报错） |

> 历史：v0.2.4 用每主机 `HostConfig.appendToken` 选择同样两条路径；v0.2.5 删除该字段，
> 旧数据里的值被忽略、不迁移，统一走全局默认（`false` = 内置 WebView）。

## 3. 数据流

### 3.1 导入 SSH 配置

```
用户粘贴/选择 config 文本
  → SshConfigParser.parse(text)         （纯函数，可单测）
  → List<HostConfig>（alias/hostName/user/port/proxyJump/identityFiles）
  → 与 HostStore 合并：alias 已存在则保 credentials/auth、刷新连接字段；否则新增
  → 提示："IdentityFile 无法在手机上读取，请在密钥管理里生成/导入密钥后选择"
```

Android 没有 `~/.ssh/config`（第三方应用无权读取），所以"读取 SSH config"落地为**导入**：
粘贴文本或用系统文件选择器选一个 `config` 文件。`Include` 在手机上无法展开，会给出警告；
可以把 include 的内容合并成一份再导入。

### 3.2 打开主机（本任务的核心行为变化）

```
点击「打开」
  ├─ DIRECT_URL 模式：直接 ACTION_VIEW(host.directUrl)     （给已自建反代/内网穿透的用户）
  └─ TUNNEL 模式：
       TunnelService.ACTION_OPEN(alias)
         ensureTunnel(host)
           ├─ 无凭证 → EVENT_FAILED(needs-credentials)
           ├─ PRIVATE_KEY 且 keyId 存在 → HostStore.findSshKey(keyId) 取私钥/口令
           ├─ 主机密钥未信任 → EVENT_NEEDS_HOSTKEY(fingerprint) → 用户确认 → 重试
           ├─ JSch connect + setPortForwardingL + HTTP 就绪探测
           └─ EVENT_OPEN(localPort)
       Activity 收到 OPEN 后（v0.2.5，不再直接把隧道 URL 交给浏览器）：
         ├─ SettingsStore.isBrowserMode == true
         │    → 隧道内 token 预校验（TunnelService，OPEN 前）
         │    → 剪贴板写入 browserUrlFor(localPort, token) → 打开浏览器首页（用户粘贴）
         └─ SettingsStore.isBrowserMode == false（默认）
              → 启动 WebViewActivity：
                GET browserUrlFor(localPort, token)（不跟随 303）
                → 取 Set-Cookie 的 dsh-auth-* → CookieManager 注入
                → WebView.loadUrl(plainUrlFor(localPort))   // URL 不含 token
                → 顶栏可收起（SettingsStore.isCollapseAppBar）
```

`browserUrlFor` 与桌面插件同形：`http://127.0.0.1:<localPort>/?token=<urlencoded-token>`；
`plainUrlFor` 返回不含 token 的 `http://127.0.0.1:<localPort>/`。若以后改成"本地 cookie 注入反代 +
外部浏览器"，可复用 WebView 路径的 token 交换/注入逻辑，只替换 WebView 载体。

### 3.3 隧道生命周期

* 每台主机至多一条隧道；重复点击复用（JSch Session 缓存）。
* 前台服务持有所有隧道；通知显示"N 台主机已连接"，动作"全部断开"。
* 远端断开/网络切换 → Session 失效 → 广播 `FAILED`；下次「打开」自动重连。
* 关闭单台：`ACTION_CLOSE(alias)`；全部关闭后 `stopSelf()` 退出前台。
* 不做自动重连（移动网络切换频繁，静默重连会掩盖问题）；用户点一次「打开」即重连。

### 3.4 内置页面顶部横栏收起与 UiInsets（v0.2.5）

```
SettingsStore.isCollapseAppBar == false
  → 无 appbar_collapse 按钮；横栏始终 56dp；不注册滚动监听
SettingsStore.isCollapseAppBar == true（默认）
  → view_app_bar.xml 显示 appbar_collapse（右侧、appbar_more 左侧）
  → 展开 56dp：返回箭头 + 18sp 粗体标题 + 副标题=alias + 收起按钮
  → 收起 32dp：仅一行 13sp 常规字重 alias，整条 app_bar 可点按展开
  → WebView.setOnScrollChangeListener：
      向下滚 > 12dp            → 收起
      scrollY ≤ 4dp 且向上滚动 → 展开
      向上滚动但不在顶部       → 不展开（避免与手动收起打架）
  → 错误面板 showError() 先展开横栏
  → 收起/展开后：
      appBar.LayoutParams.height = 基准高度 + 当前 statusBar inset
      UiInsets.setBarBaseHeight(appBar, 基准px)   // 写入 View plain tag
      root.requestApplyInsets()                   // 重新应用 inset
```

* **UiInsets 兼容机制**：`UiInsets.applyTopInset()` 优先读取 app_bar 的 plain tag（`setTag(Integer)`）
  作为动态基准高度；没有 tag 的其他页面继续使用 `apply()` 时捕获的高度，**行为完全不变**。
  这样不必为动态高度改所有 Activity 的布局契约。
* **不覆盖内容**：收起态是 32dp 的真实布局子视图（`content_area` 上方），不是悬浮层；代价是不能完全隐藏
  （完全隐藏需要始终悬浮的呼出按钮，会挡住页面内容/按钮）。
* **已知边界**：自动收起依赖系统 WebView 上报 `View.OnScrollChangeListener`；个别 ROM 不触发时仅自动收起失效，
  手动收起/点按展开仍可用。收起状态不持久化。

## 4. 认证与密钥

* **SSH 认证**：密码 / **App 密钥库中的密钥对**（v0.2.0 起为主路径；每台主机只存 `keyId`）。
* **密钥生成**：`SshKeyManager.generate()` 用 JSch 在本机生成 RSA 2048/3072/4096，私钥序列化为
  PEM（可加口令），公钥为 OpenSSH 行。App 不把私钥交给任何其他应用；推荐做法是"在 App 内生成、
  只把公钥装到远端 `~/.ssh/authorized_keys`"，这样每台设备/每个 App 一把独立密钥。
* **密钥导入**：支持 PEM / OpenSSH 格式（含口令保护）；可附带公钥行做配对校验。
* **旧数据迁移**：v0.1.0 的主机内联私钥在加载时自动迁入密钥库（相同私钥去重），主机改写为 `keyId`。
* **跳板机（ProxyJump）**：支持 `user@host:port` 与"同列表中的别名"两种写法。用目标主机的
  认证材料登录跳板（v1 限制，文档写明）；实现方式：跳板会话开一个本地转发到目标 22，
  第二个 Session 用自定义 SocketFactory 接到那个本地端口。
* **主机密钥**：TOFU。首次连接广播指纹让用户确认；之后比对保存的 base64 公钥，不匹配则
  拒绝连接并提示"主机密钥已变更"（可能是真的换了机器，也可能是中间人）。
* **DSH 凭证**：`http(s)://127.0.0.1:<remotePort>/?token=…`，只保留 `remotePort` 与 `token`。

## 5. 构建与分发（为什么没有 Gradle）

本机没有 JDK / Android SDK / Gradle，且网络只能经 Node 的 fetch 访问；因此项目自带一个
**可移植工具链**（`tools/setup-toolchain.mjs` 下载 Temurin JDK 17 + Android platform 35 +
build-tools 35 + JSch 到 `.toolchain/`），并用 `tools/build.ps1` 直接跑
`aapt2 → javac → d8 → zipalign → apksigner`。

* 无 AndroidX / 无第三方 Android 依赖 → 构建不需要联网、不需要 Gradle 依赖解析。
* APK 用项目内 `keystore/release.jks` 自签名；**同一个 keystore 保证覆盖安装可升级**。
* 分发：`dist/dsh-remote-hosts-<version>.apk` 侧载（允许"安装未知应用"/ 或 `adb install -r`）。
* 已实测（v0.2.5）：产物 `dist/dsh-remote-hosts-0.2.5.apk`，**751845 字节（约 0.72 MB）**，SHA256
  `b6c613b682a093d0aea02edf987fd5a2d2de23a1f1a16cbd53f55d511434a1d9`；构建耗时约 22 秒，
  两次独立构建 hash 一致（可复现）；`apksigner verify` + `zipalign -c 4` 通过。

## 6. 环境限制与风险（已知边界）

1. **未在真机/模拟器上运行过**：本构建环境无 Android 设备、无 emulator/HAXM。v0.2.5 已通过
   编译期检查 + **415 项桌面 JVM 单测** + 真实 SSH 测试 + **76/76 APK 静态校验**；
   真机验收步骤见 docs/install.md（新横栏收起尤其需要真机确认）。
2. 外部浏览器模式（设置页开启「通过外部浏览器连接」）中 token 会短暂进入剪贴板/地址栏/浏览器历史——
   这是远端登录握手与 Android 浏览器 SameSite 限制造成的已知暴露面；内置 WebView（默认）URL 不含 token。
3. 移动网络下 SSH 长连接掉线频繁，隧道掉线后需重新点「打开」。
4. Android 13+ 需要通知权限（前台服务通知），拒绝时服务仍可运行但无通知。
5. 部分 ROM 的后台限制可能杀前台服务；必要时在系统设置中允许自启动/无限制电量。
6. **ed25519**：JSch mwiede 2.28.x 在 Android 上能否用取决于运行时 JCA（见 docs/security.md）；
   App 生成的密钥默认 RSA，导入 ed25519 时 UI 会提示兼容性风险。
7. **横栏自动收起依赖系统 WebView 的滚动回调**：个别 ROM 不上报 `View.OnScrollChangeListener` 时，
   自动收起/自动展开不生效，手动收起与点按展开仍可用（见 docs/troubleshooting.md §16.3）。

## 7. 与桌面插件的行为映射

| 桌面 `dsh-remote-hosts` | 本 Android app |
|---|---|
| 读 `~/.ssh/config` 得 host 列表 | 导入 SSH config 文本/文件（手机无 `~/.ssh`） |
| 每主机保存 `port + token` | 加密保存 `remotePort + token`；认证走 App 密钥库 |
| `ssh -L` 子进程 | JSch 会话内本地转发 |
| `openTab('browser', {url})` | 设置页开启外部浏览器时：复制 token URL + 打开浏览器首页，用户粘贴；默认：内置 WebView 自动注入 cookie 打开 |
| 左侧栏面板 + 状态 | 主机列表 + 状态 chip + 前台服务通知 |
| 错误分类 `bad-request/unknown-host/needs-credentials/unreachable/internal` | 同名枚举 + `host-key-changed` |
| token 只在 `/remote/open` 响应的 `url` 字段 | token 只在 `CredentialUrl.browserUrlFor()` 的返回值（及内置 WebView 的内部交换请求） |
