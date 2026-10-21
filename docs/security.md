# 安全模型与隐私说明

本文说明 **DSH 远程主机** Android App 保护什么、不保护什么，以及你在使用中应该注意什么。
内容对应实现（`app/src`、`app/AndroidManifest.xml`）与行为契约（[docs/app-spec.md](app-spec.md)），
并引用桌面端 `dsh-remote-hosts` 的既有安全口径（`../remote-hosts/README.md`、
`../remote-hosts/docs/web-proxy-plan.md`）。

> 一句话：**凭证与 SSH 私钥在手机里加密保存，SSH 隧道只绑定本机回环地址；v0.2.5 默认由 App 内置 WebView
> 完成登录，交给 WebView 的地址不含 token；只有设置页开启「通过外部浏览器连接」时才会把含 token 的完整网址
> 经敏感剪贴板交给外部浏览器，由你粘贴打开。**
> v0.2.0 起 **App 自带 SSH 密钥库**：推荐在 App 内生成手机专用密钥，只把**公钥**装到远端，
> 不和电脑/其他 App 共用私钥。
> v0.2.5 的设置页只保存两个**非敏感布尔值**（外部浏览器模式、横栏收起），不保存任何凭证。
> 但 App 不是「安全边界本身」——手机丢失、手机被 root、恶意输入法/剪贴板等风险仍然存在。

---

## 1. 保护的对象（资产清单）

| 资产 | 在哪 | 泄露后果 |
|---|---|---|
| **App 管理的 SSH 私钥 + 口令** | 手机加密存储（`hosts.enc` 的 `sshKeys[]`） | 他人可登录该密钥授权的所有服务器 |
| SSH 登录密码（密码认证的主机） | 手机加密存储（`hosts.enc` 的 `hosts[]`） | 他人可登录你的 SSH 主机 |
| DSH 访问 token | 手机加密存储；打开时进入 App 内部的 token 交换请求 URL；**设置页开启外部浏览器模式**时还会经剪贴板进入外部浏览器地址栏/历史 | 他人可在隧道存活期间访问远端 DSH Web UI |
| WebView 登录 cookie（`dsh-auth-*`，内置 WebView） | App 的 WebView CookieManager（应用私有存储） | 他人可在隧道存活期间通过该回环端口访问远端 DSH Web UI |
| 已信任主机密钥（指纹表） | 手机加密存储（`trustedHostKeys`） | 影响中间人防护判断（被篡改则会误信任攻击者） |
| 主机元数据（别名、地址、端口、用户名、keyId） | 手机加密存储 | 泄露你的内网结构与密钥使用关系 |
| **公钥 / 公钥指纹** | 明文可公开（详情页可复制） | 无（公钥本身不是秘密；但可暴露你用了哪些主机，视场景而定） |

App **不收集**：位置、通讯录、短信、相册、设备标识符、使用统计、崩溃上报。没有遥测，没有广告 SDK。

---

## 2. 凭证与密钥如何保存

* 加密算法：**AES-256-GCM**，密钥由 **Android Keystore** 生成并保管，别名 `dsh-mobile-store`，
  **不可导出**（应用自己都读不到密钥字节）。
* 落盘位置：应用私有目录 `files/hosts.enc`。普通应用（没 root 的前提下）读不到别的 App 的私有目录。
* 文件内容：**主机列表、SSH 密钥库（`sshKeys[]`，含私钥与口令）、已信任主机密钥表** 在同一个
  JSON 里，一起加密、一起原子写入——三者不会出现只写了一半的状态。
* 文件格式：`MAGIC(4) | VERSION(1) | IV(12) | ciphertext`；每次写入使用**随机 IV**。
* 写盘使用**临时文件 + 重命名**的原子写，避免断电/崩溃留下半个文件。
* 内存中解密后的对象只存在于 App 进程内，供列表/隧道使用；不会写到 SharedPreferences、外部存储或日志。
* **v0.2.5 的全局设置是唯一写入 SharedPreferences 的数据**：`core/SettingsStore` 使用 App 私有目录的
  `settings` 文件，只含 `browser_mode` / `collapse_app_bar` 两个布尔值。该文件**未加密**，但**不包含任何秘密**
  （没有 token、密码、私钥或完整 URL）；凭证仍只存在于上面的加密 `hosts.enc` 中。
* Android 清单里设置了 `android:allowBackup="false"`：**不参与系统备份/云备份**。
* **卸载即销毁**：卸载会删除应用数据且 Keystore 中的密钥失效，`hosts.enc` 即使被留档也解不开。

> 这是「防手机丢失后被直接读文件」「防其他普通应用偷看」级别的保护；**不防**已 root 的手机、
> 系统级恶意软件、或趁解锁时直接操作你手机的人。

---

## 3. SSH 密钥生命周期（v0.2.0）

### 3.1 生成（推荐路径）

* 在 App 内 **SSH 密钥管理… → 生成新密钥**，使用内置 JSch 在本机生成 **RSA 2048/3072/4096**
  （默认 3072）；生成的私钥是**无口令 PEM**（生成流程不设口令），安全性由整个密钥库的
  AES-256-GCM/Keystore 加密存储保证。导入的私钥则**保留原有的口令与格式**（PKCS#1 PEM、
  OpenSSH v1 的 RSA/ECDSA/ed25519 均可解析）。公钥为 OpenSSH 行。
* **私钥从生成起就只存在于 App 的加密存储**：不写文件、不放进 Intent、不传给浏览器；
  生成后界面只展示**公钥**（可复制）。
* 安全建议：**每台设备 / 每个 App 用一把独立密钥**，不要和电脑上的 `~/.ssh/id_rsa` 共用；
  密钥名（label）用可识别的名字（如 `pixel-dsh`），方便在服务器上审计与吊销。

### 3.2 导入（复用已有密钥）

* 导入的私钥会进入同一个加密存储；可选择填入口令（有口令的私钥必须填对，否则导入被拒）。
* 可选提供公钥行做**配对校验**：公钥与私钥不匹配会直接拒绝（不会保存一个用不了的密钥）。
* **指纹**：`SHA256:` + 标准 base64（sha256(公钥 blob)，无 `=` 填充）。与 `ssh-keygen -lf`
  （如 `ssh-keygen -lf ~/.ssh/id_rsa.pub`、`ssh-keygen -lf ~/.ssh/authorized_keys`）输出的
  SHA256 指纹**完全一致**，可直接核对。
* 导入路径的风险主要在 App 之外：私钥要先出现在手机文件系统/剪贴板里才能粘贴或选文件。
  **导入完成后请删除临时私钥文件、清空剪贴板**；不要把私钥长期放在聊天软件/网盘里。
* 兼容性：RSA / ECDSA 一般可用；**ed25519 能否使用取决于手机系统提供的加密实现**，
  部分旧 Android 不支持。不确定时请用 App 生成的 RSA 密钥。

### 3.3 使用

* 主机只保存 `keyId`（引用），私钥材料在隧道连接时才从加密存储解出、放入内存交给 JSch；
  不会写临时文件、不会进 Intent、不会进通知/广播/日志。
* 多台主机可以引用同一把密钥；轮换密钥时只需在密钥管理里导入/生成新密钥并在主机里改选。

### 3.4 查看与复制

* **公钥可以公开**：详情页「复制公钥」是安全操作，用于粘贴到远端 `~/.ssh/authorized_keys`。
* **私钥是秘密**：默认隐藏，点「显示私钥」需要二次确认；「复制私钥」会给出敏感信息警告。
  * App 在 **Android 13+（API 33）** 会把私钥剪贴板内容标记为 `ClipDescription.EXTRA_IS_SENSITIVE`，
    让系统剪贴板 UI 隐藏预览；更早的 Android 没有这个标记。
  * 复制后请尽快粘贴到目标位置并**清空剪贴板**；不要把私钥粘贴进聊天软件、邮件或网页表单。
* App 未设置 `FLAG_SECURE`：屏幕上显示的私钥/口令可能被系统截图或录屏捕获，
  在他人可见的环境下请勿打开密钥详情或编辑页。

### 3.5 删除与吊销

* 删除密钥前，App 会统计并提示**有多少台主机正在引用它**；删除后这些主机将**无法用私钥登录**
  （连接会报「所选密钥已不存在，请重新选择」），需要重新生成/导入密钥并在主机里重新选择。
* **密钥本身不能被远端“删除”**：远端只保存公钥行。真正吊销一把密钥 = 在远端
  `~/.ssh/authorized_keys` 里删掉对应的公钥行，再在 App 里删除/替换该密钥。
* 手机丢失时优先做远端吊销（见 §10），App 内的删除是第二道防线。

### 3.6 从 v0.1.0 迁移（旧内联私钥）

* 加载旧数据时，每把**不同的**旧内联私钥会迁移成密钥库条目（按私钥文本去重），
  主机改为引用迁移后的密钥并**清空**旧的内联私钥字段（同一份私钥不再重复保存）。
* 迁移在加密文件内部完成，迁移结果立即原子写回；**无法解析的旧私钥会保留在该主机上继续可用**
  （迁移失败不丢主机数据，但该密钥不会进入密钥库）。
* 迁移后的私钥与用户手动导入的私钥受到**同等加密保护**。
* **v0.2.0 → v0.2.1 无数据迁移**：密钥库/主机数据原样保留；升级只增加 token 预校验行为（见 §4）。
* **v0.2.1 → v0.2.2 无数据迁移**：新增 `appendToken` 字段对旧数据默认 `true`（保持原有带 token 行为）；
  v0.2.4 起新主机默认 `false`，此时由 App 内置 WebView 自动完成登录（见 §4.1）。
* **v0.2.2 → v0.2.3 无数据迁移**：只改「附加 token」时的交接交互（该版本的 token 交接对话框已被 v0.2.4 删除），
  数据与凭证格式不变。
* **v0.2.3 → v0.2.4 无数据迁移**：数据与凭证格式不变；打开方式改为「模式 B：App 内置 WebView 全自动打开（新主机默认）」
  与「模式 A：复制含 token 网址 + 浏览器地址栏粘贴」两种，已存在主机保留原 `appendToken` 值。
* **v0.2.4 → v0.2.5 无数据迁移**：同一签名覆盖安装，加密存储原样保留；`appendToken` 字段从模型/JSON 删除，
  旧文档里残留的该字段在解码时被**忽略**（不读入、不再写回）。每主机的选择被丢弃，
  打开方式统一由全局 `SettingsStore` 决定（默认 `false` = 内置 WebView）；设置文件只含布尔值，不含凭证。

---

## 4. Token 的出口与 WebView 安全边界

DSH 的 token（`dsh web` 启动时打印的 `?token=…`）是访问远端 Web UI 的凭据。实现上：

* **唯一允许把 token 拼进 URL 的函数**：`core/CredentialUrl.browserUrlFor(localPort, token)`
  （见 `app/src/dev/dsh/remotehosts/core/CredentialUrl.java`）；另有
  `CredentialUrl.plainUrlFor(localPort)`：只返回不含 token 的 `http://127.0.0.1:<port>/`（内置 WebView 用它加载页面）。
* **全局设置不接触 token**：`core/SettingsStore` 只读写 `browser_mode` / `collapse_app_bar` 两个布尔值
  （SharedPreferences 文件 `settings`，App 私有、未加密但无秘密）。打开方式判定只读这个布尔值，不读取凭证。
* 用户粘贴的完整凭证 URL 只会被解析出 `端口 + token` 两项，**原始 URL 不保存**。
* token **不写日志**、**不进通知**、**不拼进异常消息**、**不写明文配置文件**；异常/广播消息出口会经过
  `LogSanitizer` 把 token/密码/私钥/口令替换为 `[redacted]`。
* 界面上如果必须展示 token（例如需要你核对），只显示 `maskToken` 掩码（前 3 位 + `…` + 后 3 位）。
* token **不进任何 Intent**：内置 WebView 模式启动 `WebViewActivity` 时只传 `alias` + `localPort`，Activity 自己从加密存储取 token。
* 长按主机菜单的 **「复制打开网址（含 token）」**（隧道 OPEN 时可用）会把 `browserUrlFor` 的结果放到系统剪贴板，
  属于**用户主动触发的敏感操作**，复制后 App 给出剪贴板提示，不写日志。

### 4.1 内置 WebView（默认）：App 全自动登录

`SettingsStore.isBrowserMode == false`（默认，见 §3.6 升级说明）时，点「打开」→ 隧道就绪后：

1. App 在后台线程用 `HttpURLConnection` 请求 `CredentialUrl.browserUrlFor(localPort, token)`
   （不跟随 303，connect/read 超时各 5s）；从响应头 `Set-Cookie` 中找到 `dsh-auth-*` 的 `name=value`；
2. 通过 `CookieManager` 把该 cookie 只设置给 `http://127.0.0.1:<localPort>/`（`Path=/; SameSite=Lax; HttpOnly`）并 `flush()`；
3. `webView.loadUrl(CredentialUrl.plainUrlFor(localPort))`——**加载的网址不含 token**。

安全性质：

* **token 只出现在 App 进程内部的这一次 token 交换请求 URL 里**；它不进入 WebView 的地址栏/历史，
  也不经过外部浏览器、不经过剪贴板。
* **cookie 只存在于 App 自己的 WebView 存储（`CookieManager` / WebView cookie jar）**，不会传给任何外部浏览器；
  App 也从不把 cookie 放进 Intent/通知/日志。
* WebView 属于手机系统的 **Android System WebView / Chrome** 组件（不随 APK 打包），安全更新跟随系统更新；
  App 不调用 `setWebContentsDebuggingEnabled`（不开放远程调试）。
* WebView 配置：`JavaScriptEnabled` / `DomStorageEnabled` / `DatabaseEnabled` 打开（DSH UI 需要）；
  `AllowFileAccess=false`、`AllowContentAccess=false`、`MixedContentMode=MIXED_CONTENT_NEVER_ALLOW`；
  页面内的其他 http(s) 链接**交给系统浏览器**打开（不在 WebView 内继续），其他 scheme 交给系统处理；
  主框架加载失败显示错误面板；`onDestroy` 时移除并 `destroy()` WebView。
* **信任边界**：WebView 里运行的是**远端 DSH 实例返回的页面代码**（含 JS）。这与你在电脑浏览器打开同一个
  `dsh web` 的信任模型一致——**是否可信取决于那台远端主机**；App 的 SSH/凭证加密不能替代对远端主机的信任。
* token 交换失败（401/403 / 无 cookie / 连接失败）时**不会加载页面**，而是显示带
  **「去编辑凭证 / 重试 / 关闭」** 的错误面板，错误文案不回显 token。

### 4.2 外部浏览器模式：复制含 token 网址 + 浏览器粘贴

`SettingsStore.isBrowserMode == true`（设置页「通过外部浏览器连接」开启）时，点「打开」→ 隧道就绪后：

1. App 先在隧道内做 token 预校验（见 §5.1）——401/403 直接报错，**不会**复制网址/打开浏览器；
2. 预校验通过后，把 `CredentialUrl.browserUrlFor(localPort, token)` 的完整 URL（含 token、**原封不动**）
   放入系统剪贴板（Android 13+ 标记 `ClipDescription.EXTRA_IS_SENSITIVE`），并打开默认浏览器**首页**
   （`Intent.makeMainSelectorActivity(ACTION_MAIN, CATEGORY_APP_BROWSER)`；打不开只 Toast，**不回退到 URL 直开**）；
3. 你在浏览器地址栏粘贴打开，浏览器自己完成 token→cookie 交换（远端 303 + `Set-Cookie`）。

**App 从不向外部浏览器传递 cookie**，只传递 URL 文本。v0.2.4 已**彻底删除**「App 用 `ACTION_VIEW`
直接打开隧道网址」的路径（「直接网址」模式除外）；该路径在 Android 浏览器上会因 SameSite 策略 401。

安全提示：**告警与帮助现在位于设置页该开关处**——开启「通过外部浏览器连接」时会弹一次说明/安全告警，
开关旁有 **?** 帮助入口（不再有每主机开关旁的 ?）。复制出的网址含 token，**粘贴打开后请清空剪贴板**，
不要把网址转发/截图。

### 4.3 已知暴露面：外部浏览器模式的 token 会短暂出现在浏览器地址栏

只有外部浏览器模式（设置页开启后）会把下面这个 URL 交给**外部浏览器**（由你粘贴）：

```text
http://127.0.0.1:<隧道本地端口>/?token=<token>
```

远端 DSH 收到带 token 的 `GET /` 后，会 `Set-Cookie: dsh-auth-*` 并 **303 跳转**到干净的 `./`，
所以 token **不会长期停留在地址栏**。但在这几秒内，token 可能出现在：

* 浏览器**地址栏**；
* 浏览器**历史记录**（以及页面缓存）；
* 系统**剪贴板**（App 复制完整 URL 时；Android 13+ 有敏感标记，但剪贴板内容仍可被有权限的 App/输入法读取）。

因此：

* 只在需要外部浏览器时开启该设置（日常用默认的内置 WebView，token 不经过剪贴板和外部浏览器）；
* 复制后**尽快粘贴打开并清空剪贴板**；不要把复制出的网址发给别人、不要截图；
* token 失效/更换后，旧网址（旧 token）不再可用；远端重启 `dsh web` 会换新 token。

> 内置 WebView 不扩大这个暴露面：WebView 加载的 URL 不含 token，cookie 由 App 注入到自己的 WebView。
> 与桌面插件 `dsh-remote-hosts` 的 P1 方案（带 token 的 URL 交给浏览器）相比，日常路径已经不再需要
> URL 携带 token；未来若要连外部浏览器模式也去掉 URL 里的 token，需按桌面端 P2 方案在隧道之上加
> 「本地 Cookie 注入反向代理」——当前版本（0.2.x）**没有**实现 P2 反代，内置 WebView 的 CookieManager
> 注入是 App 内 WebView 专用实现。

---

## 5. 网络连接与暴露面

### 5.1 App 主动发起的连接

| 连接 | 方向 | 说明 |
|---|---|---|
| SSH 到远端（或跳板机） | 出站，用户配置的端口 | JSch，加密 + 主机密钥校验 |
| 到 `127.0.0.1:<本地端口>` 的就绪探测 | 本机回环 | 仅用于判断隧道是否已能通过 HTTP 访问（任何状态码都算就绪） |
| 到 `127.0.0.1:<本地端口>/?token=…` 的 token 预校验（仅外部浏览器模式：`SettingsStore.isBrowserMode=true`） | 本机回环（经 SSH 隧道到远端） | 隧道建立后、复制网址/打开浏览器前的 `GET`（不跟随 303）；401/403 → 直接提示 token 失效，**不复制网址、不打开浏览器** |
| 到 `127.0.0.1:<本地端口>/?token=…` 的 token 交换（内置 WebView，`WebViewActivity` 后台线程） | 本机回环（经 SSH 隧道到远端） | 不跟随 303，connect/read 各 5s；只取响应头 `Set-Cookie` 里的 `dsh-auth-*` 注入 App 内 WebView；401/403/IO/无 cookie → 错误面板（token 不落日志） |
| WebView 加载 `http://127.0.0.1:<本地端口>/`（默认） | 本机回环（经 SSH 隧道到远端） | 页面由 **App 自己的 WebView** 加载，URL **不含 token**；页面内其他 http(s) 外链交给系统浏览器 |

App **不访问**其他服务器：没有账号体系、没有云同步、没有更新检查、没有统计上报。
密钥生成/导入完全在本机完成（JSch/JCA 本地运算），不联网。

### 5.2 App 在本机监听的端口

* 隧道通过 `Session.setPortForwardingL("127.0.0.1", localPort, "127.0.0.1", remotePort)` 实现，
  **只绑定 `127.0.0.1`（本机回环）**，不会绑定 `0.0.0.0`，同一 Wi-Fi 下的其他设备无法直接连进来。
* 本地端口从 20000–60000 中探测空闲端口，避免与常见服务冲突。
* **本机上的其他 App 在隧道存活期间也能连接 `127.0.0.1:<localPort>`**（这是所有本地转发都存在的
  本地信任边界）。远端 DSH 仍要求正确的 token/cookie 才能使用，因此这不算「绕过登录」，
  但如果你把手机借给别人、或安装了不可信的 App，隧道存活期间存在被本机访问的可能。
  **不用时点「全部断开」**可以消除这个窗口。
* **内置 WebView 是 App 进程内的组件**：它直接访问 `127.0.0.1:<localPort>`，不是对外服务；
  WebView 的 cookie 存在 App 私有存储里，与系统浏览器或其他 App 的 profile 不共享。

### 5.3 明文 HTTP 与网络安全配置

* 隧道内是明文 HTTP（`http://127.0.0.1:...`），但这段流量**跑在 SSH 加密通道里**，
  手机上只到回环地址，不经过 Wi-Fi/移动网络。内置 WebView 加载的也是这个回环地址，同样走 SSH 隧道。
* `app/res/xml/network_security_config.xml` 只对 `127.0.0.1` / `localhost` 放行明文；
  App 自身的网络栈不会对任意外部地址使用明文 HTTP。
* **「直接网址」模式**（DIRECT_URL）不经过 SSH 隧道：App 只是把你的网址交给浏览器打开。
  如果你填的是 `http://` 外部地址，请自行评估链路安全，**建议使用 HTTPS**；
  优先用反向代理 / Tailscale 等已有加密通道。

---

## 6. 主机密钥：TOFU（首次使用即信任）

SSH 隧道最容易出问题的地方是「连到了假服务器」。本 App 的处理：

1. 首次连接某台主机时，**不自动信任**，而是把服务器公钥的 **SHA256 指纹**展示给你，由你人工核对
   （`确认 SSH 主机密钥` 对话框）。
2. 你点「信任并连接」后，App 保存 `host:port`、密钥类型、公钥 base64、指纹和时间。
3. 以后连接时比对已保存的公钥；**不一致就拒绝连接**并报「主机密钥已变更」，
   不给你「静默继续」的选项——这可能是管理员真的换了密钥，也可能是中间人攻击。
4. 可以随时在编辑页 **重置此主机的密钥信任**，或菜单 **「更多」（⋮）→ 已信任的主机密钥… → 清除全部信任**。
5. 使用 **ProxyJump（跳板机）** 时，**跳板机与目标主机的密钥分别独立校验**：首次连接会先确认跳板机指纹、
   再确认目标主机指纹（两次 TOFU 确认）。任何一次密钥变化都会被拦截。

核对指纹的方法（在远端机器上执行，路径按系统实际文件调整）：

```bash
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
ssh-keygen -lf /etc/ssh/ssh_host_rsa_key.pub
```

> 注意：本 App 的主机密钥信任表是**独立于桌面端 `~/.ssh/known_hosts`** 的，两者互不同步。
> 另外，**App 管理的用户密钥**（§3）与**服务器主机密钥**（本节）不是一回事：
> 前者是「你登录服务器用的」，后者是「服务器证明自己身份用的」，不要混淆。

---

## 7. 权限清单（最小化）

`app/AndroidManifest.xml` 只申请以下权限：

| 权限 | 用途 | 是否可拒绝 |
|---|---|---|
| `INTERNET` | 建立 SSH 连接、访问回环隧道 | 不可（没有它 App 无法工作） |
| `ACCESS_NETWORK_STATE` | 判断网络是否可用，给更准确的错误提示 | 不可 |
| `FOREGROUND_SERVICE` | 隧道需要前台服务跨界面存活 | 不可 |
| `FOREGROUND_SERVICE_DATA_SYNC` | 声明前台服务类型为 `dataSync`（Android 14+ 要求） | 不可 |
| `POST_NOTIFICATIONS` | Android 13+ 显示隧道常驻通知和「全部断开」按钮 | **可拒绝**，不影响隧道功能，只是没有通知 |

**没有**申请：存储/相册、联系人、位置、相机、麦克风、拨打电话、读取短信、查询已安装应用等权限。
打开本地文件（导入 config、导入私钥文件）使用系统文件选择器（SAF），App 只获得你选中那一个文件的临时读取权，
**不需要**存储权限。生成/保存 SSH 密钥也不需要任何额外权限（全部在应用私有目录 + Keystore 内完成）。

组件暴露面：

* `MainActivity` 为启动入口（`exported=true`，仅带 MAIN/LAUNCHER intent-filter）；
* `EditHostActivity`、`ImportConfigActivity`、**`SshKeysActivity`（v0.2.0 密钥管理）**、
  **`SettingsActivity`（v0.2.5 全局设置）**、**`WebViewActivity`（v0.2.4 内置 WebView 打开）**、`TunnelService`
  均为 **`exported=false`**，其他 App 无法直接启动它们或调用服务；`WebViewActivity` 的 Intent 只带
  `alias` + `localPort`（不含 token）。
* 服务广播为**应用内显式广播**（`EVENT_TUNNEL`），接收方使用 `RECEIVER_NOT_EXPORTED`，外部应用收不到。

---

## 8. 日志与错误信息

* 代码中**禁止**打印 token、密码、**SSH 私钥、私钥口令**；已知产生的内部错误消息在进入 UI/广播前会经过
  `core/LogSanitizer` 过滤（把敏感值替换为 `[redacted]`）。引擎在解析密钥库材料后，会把该密钥的私钥与口令
  加入脱敏 secrets，再输出任何消息。
* 密钥管理界面**不写日志**：生成/导入/查看/复制路径都不会把私钥或口令写到 logcat、通知或 Intent。
* 如果你在排查问题时需要抓系统日志（`adb logcat`），注意日志里仍可能出现主机地址、用户名、公钥指纹等
  **元数据**（不是私钥/密码/token）。分享日志前请自行检查、遮蔽敏感路径和主机名。
* App 不做崩溃上报。崩溃只会留在系统日志里（不会自动上传）。

---

## 9. 已知限制与残余风险（诚实清单）

1. **外部浏览器模式的 token 会短暂进入外部浏览器地址栏/历史**：见 §4.3；内置 WebView（默认）加载地址不含 token。
2. **隧道存活期间本机回环端口可被本机其他 App 访问**：见 §5.2，属于本地信任边界。
3. **不做自动重连**：网络切换后隧道可能已失效，但 App 不会静默重连；重新点「打开」即可。
4. **前台服务可能被厂商 ROM 杀掉**：通知消失并不意味着凭证/密钥丢失；按
   [docs/install.md §5](install.md#5-前台服务与省电设置建议做) 设置省电白名单可降低概率。
5. **v0.2.5 的 UI 新特性仍待真机确认**：横栏收起的自动滚动行为依赖系统 WebView；APK/JVM 验收已通过
   （76/76、415 checks），但真机表现见 [README](../README.md#7-已知限制别踩坑)。
6. **无加固/混淆/root 检测**：APK 可被反编译查看逻辑（里面**没有**内置任何真实凭证、私钥或 token）。
   安全依赖 Android 平台隔离与 Keystore，而不是代码混淆。
7. **密钥没有云端备份/导出文件功能**（有意设计）：卸载/换机会丢密钥；换机需要重新生成并在远端更新公钥。
8. **ed25519 兼容性**：导入的 ed25519 私钥能否使用取决于系统加密实现；App 生成的是 RSA（兼容性最好）。
9. **剪贴板敏感标记只在 Android 13+ 生效**：更早系统的剪贴板可能显示私钥预览/被其他 App 读取
   （Android 10+ 起后台 App 读取剪贴板受限，但仍有风险）。复制私钥后请尽快清空剪贴板。
10. **屏幕截图/录屏**：App 未禁止截屏，密钥详情/编辑页可能被截取；私钥默认隐藏但可被二次确认显示。
11. **外部浏览器模式的 token 预校验不是浏览器 cookie 的保证**：App 只验证「远端接受这个 token」（GET 不跟随 303），
    浏览器是否成功保存/携带 cookie 还取决于浏览器实现；v0.2.4 已删除「App 直接跳浏览器」的路径，
    外部浏览器模式必须由你**在地址栏粘贴**打开（用户发起导航），否则会 401。内置 WebView 由 App 自己完成握手并注入 cookie，
    不受该限制（见 [docs/troubleshooting.md §7](troubleshooting.md#7-打开异常webview-失败--401--authentication-required)）。
12. **外部浏览器模式 / 「复制打开网址」的剪贴板风险**：含 token 的完整 URL 会进入系统剪贴板
    （用户显式触发 + 敏感标记），token 可能进入剪贴板历史、浏览器地址栏/历史；其他有剪贴板权限的
    App/输入法有读取风险。粘贴完请清空剪贴板，不要把网址发到聊天/邮件或截图分享。设置页开启该开关时会弹一次告警。
13. **内置 WebView 的残余风险**：页面 JS 在系统 WebView 沙箱内运行，App 无法审查远端 DSH 返回的
    页面代码（信任边界是远端主机本身）；系统 WebView 组件过旧/被停用可能加载失败（按 [§7.2](troubleshooting.md#72-模式-bwebview失败面板与排查) 排查）；
    WebView 未开启远程调试，file/content 访问关闭，但 WebView 组件自身的漏洞仍依赖系统安全更新。
14. **手机系统被完全攻破时，任何 App 都无法保证凭证安全**——此时应视同凭证已泄露，按 §10 的应急步骤处理。

---

## 10. 手机丢失 / 凭证疑似泄露时的应急步骤

按顺序执行（都在电脑/远端的 SSH 会话里做，不要用丢失的手机）：

1. **吊销 SSH 访问**：
   * 在远端 `~/.ssh/authorized_keys` 删掉**这台手机的公钥**那一行（用 App 生成的密钥时，用到它的主机都要删）；
   * 如果用的是密码认证，立即修改该用户密码；
   * 如果你曾在 App 里导入过与电脑共用的私钥：按「私钥可能已泄露」处理，在**所有**授权过该公钥的服务器上
     删除对应公钥。
2. **更换 DSH token**：重启远端的 `dsh web` 进程（通常会生成并打印新的 token），
   让旧 token 失效；必要时重启整台会话。
3. **检查登录与访问日志**：查看远端 SSH 认证日志（如 `/var/log/auth.log`、`journalctl -u ssh`）
   是否有异常来源的登录（重点看用该公钥的登录记录）。
4. **远程清除手机数据**：如果手机开启了厂商的「查找设备」功能，可远程锁定/擦除手机；
   若手机还在手里，直接卸载 App（凭证、密钥、信任表随之销毁）。
5. **复盘**：如果手机被 root 或安装过不明 App，建议轮换该手机上用过的所有凭证与密钥。

> App 生成的密钥「每设备一把」的好处在这里体现：吊销范围小，不需要动电脑上的其他密钥。

---

## 11. 构建/签名的安全说明（给维护者）

* APK 使用项目内 `keystore/release.jks` **自签名**，这是本项目的测试签名密钥，不是应用商店发布密钥；
  它保证「同一个 keystore 构建的 APK 能覆盖升级」（0.1.0 → 0.2.0 也依赖这一点）。
* 构建脚本里包含该测试 keystore 的密码（仅用于本地构建）。**不要把包含 keystore 的目录公开分发后，
  又用同一把密钥发布正式版本**；如果要正式发布，应重新生成密钥并妥善保管。
* APK 中**不包含**任何真实 token、密码、私钥或用户主机信息；构建后可用 `tools/check-apk.ps1`
  做静态检查（见 [docs/build.md](build.md)）。
* 应用没有动态代码加载、没有插件热更新，因此不存在「远程下发代码」的通道。**v0.2.4 起的内置 WebView 使用系统
  WebView 加载远端 DSH 返回的页面代码**——该代码来自你所连接的远端 `dsh web`（与在浏览器访问同一实例的
  信任模型一致），不是由本 APK 或任何分发渠道下发的；WebView 组件本身随系统/应用商店更新。
  APK 内不含 WebView 内核，也不含任何页面脚本。
