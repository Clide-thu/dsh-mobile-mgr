# v0.2.4 交接文档（上下文压缩 / 给新子智能体的唯一事实来源）

> 目的：这是一次**上下文压缩**产物。本轮工作请只读本文件 + 必要的目标源码，不要依赖聊天历史。
> 版本目标：**v0.2.4 / versionCode 6**（Manifest 已升版；Lead 已加 `.ui.WebViewActivity` 声明）。

## 1. 项目是什么

`dev.dsh.remotehosts`：Android 原生 App（纯 Java 8、无 AndroidX/Gradle、无第三方 Android 依赖），
在手机上管理远端 DSH（DeepSeek Harness）主机：导入 SSH config → 保存凭证 → 通过 SSH 隧道把远端
`dsh web` 端口映射到手机回环 → 打开其 Web UI。构建方式见 docs/build.md（aapt2/javac/d8/zipalign/apksigner，
工具链在 `.toolchain/`，版本信息 `.toolchain/toolchain.json`）。

当前交付：v0.2.3（已验证）。源码目录：`app/src/dev/dsh/remotehosts/{core,ui}/`、`app/res/`、
`app/AndroidManifest.xml`；测试 `test/jvm/`；脚本 `tools/`；文档 `docs/`；APK 在 `dist/`。

## 2. 用户最新决定（本轮唯一需求）

用户真机验证结论：**手动把 `http://127.0.0.1:<隧道端口>/?token=…` 粘到 Edge 地址栏能正常登录**；
而 App 用 `ACTION_VIEW` 直接跳浏览器会 401（Android 浏览器把"外部 App 发起 + 303 重定向"判为 cross-site，
拦截远端 `SameSite=Strict` 的 `dsh-auth-*` cookie；Chrome/Firefox/Edge 都一样）。App 从不向浏览器传 cookie。

用户要求（**完全替换 v0.2.3 的行为，不再询问**）：

- **A. 勾选「打开时在网址中附加 token」**（`HostConfig.appendToken == true`）：
  隧道就绪后**直接**把 `http://127.0.0.1:<隧道端口>/?token=<token>`（唯一出口
  `CredentialUrl.browserUrlFor`，原封不动）写入敏感剪贴板，并**直接打开默认浏览器首页**
  （`Intent.makeMainSelectorActivity(ACTION_MAIN, CATEGORY_APP_BROWSER)`，失败回退
  `ACTION_VIEW(token URL)`），Toast 提示"网址已复制，请粘贴到浏览器地址栏打开"。**不再弹交接对话框**。
  相关告警/说明改到**勾选开关时**弹出一次，或在开关旁加 **?** 帮助按钮（两者都做）。
- **B. 不勾选「附加 token」**（`appendToken == false`）：**不再走浏览器**，改为**内置 WebView 全自动打开**
  （见 §3）。这是新的默认推荐路径（新主机默认不勾选；旧数据/已存在主机保持原值，由用户自行切换）。
- **完全删除"把隧道 URL 直接交给浏览器"的路径**：不再有 `ACTION_VIEW(tokenUrl/plainUrl)` 的直接打开、
  不再有 v0.2.3 的交接对话框与「直接打开」按钮。保留：复制含 token 网址到剪贴板 + 打开浏览器首页（A）；
  内置 WebView（B）。**DIRECT_URL 模式保持原样**（它本身就是外部网址，用 `ACTION_VIEW`）。
- 长按主机菜单「复制打开网址（含 token）」保留（复用同一剪贴板方法）。

## 3. B 路径技术方案（WebView，系统组件，不打包浏览器）

新增 `ui/WebViewActivity`（Manifest 已声明，exported=false，label=`@string/webview_title`，
`configChanges=orientation|screenSize|keyboardHidden`）。Intent extras 只传 `alias` + `localPort`
（**绝不把 token 放进 Intent**），Activity 自己从 `HostStore.get(this).find(alias)` 取 token。

流程（全部在后台线程完成后再 loadUrl，UI 线程只更新视图）：

1. 用 `HttpURLConnection` 请求 `CredentialUrl.browserUrlFor(localPort, token)`：
   `setInstanceFollowRedirects(false)`，connect/read timeout 5000ms，GET；
   - 401/403 → 失败：提示"远端拒绝了 token（可能已失效：dsh web 重启会换 token）。请在远端重新复制
     dsh web 打印的 URL 更新凭证。"，提供按钮「去编辑凭证」/「关闭」；
   - IO/超时 → 失败："无法通过隧道读取远端 dsh web 响应"，提供「重试」/「关闭」；
   - 2xx/3xx → 从响应头 `Set-Cookie`（`getHeaderFields()`，key 忽略大小写）中找以 `dsh-auth-` 开头的
     `name=value`（`split(';')[0]`）；找不到 → 失败："远端未返回登录 cookie"。
2. 注入 cookie：`CookieManager cm = CookieManager.getInstance(); cm.setAcceptCookie(true);
   cm.setAcceptThirdPartyCookies(webView, true);
   cm.setCookie("http://127.0.0.1:" + localPort + "/", cookiePair + "; Path=/; SameSite=Lax; HttpOnly");
   cm.flush();`
3. `webView.loadUrl(CredentialUrl.plainUrlFor(localPort))`（URL **不含 token**）。
4. WebView 配置（安全）：
   - `JavaScriptEnabled(true)`、`DomStorageEnabled(true)`、`DatabaseEnabled(true)`；
   - `AllowFileAccess(false)`、`AllowContentAccess(false)`、`MixedContentMode=MIXED_CONTENT_NEVER_ALLOW`；
   - `MediaPlaybackRequiresUserGesture(false)`；不要开启 `setWebContentsDebuggingEnabled`。
   - `WebViewClient.shouldOverrideUrlLoading`：host 是 `127.0.0.1` 且 port == localPort → 在 WebView 内加载
     （允许路径/查询变化）；其他 http/https → `Intent.ACTION_VIEW` 交给系统浏览器；其他 scheme →
     尝试外部处理，失败 toast。
   - 主框架 `onReceivedError` → 显示错误面板（重试 = 重新执行 1–3）。
   - `WebChromeClient`：实现 `onJsAlert/onJsConfirm/onJsPrompt`（DSH UI 可能用到），其余默认。
   - `onPageStarted/onPageFinished` 控制进度条；`onBackPressed`：`webView.canGoBack()` → goBack，否则 finish。
   - Activity `onDestroy`：从 parent 移除 WebView 并 `destroy()`。
5. UI：复用项目既有自定义顶栏风格（`AppBar`/`view_app_bar.xml`，可加返回键 + 标题"DSH 远端主机"），
   内容区全屏 WebView + 顶部细进度条 + 错误面板（可见性控制）。**必须保留现有 `root_view` /
   `app_bar` / `content_area` 结构约定**（见 `activity_main.xml` 注释与 `UiInsets`），否则顶部遮挡修复失效。

`MainActivity` 改动：收到 `STATE_OPEN` 时
- `appendToken == true` → 组装 token URL → `copyUrlToClipboard(url, false)` → `openBrowserHomeWithFallback(url)` →
  Toast（**无对话框**；`openBrowserHomeWithFallback` 已有实现可保留，但把回退从"直接打开 URL"改为
  "Toast 提示无法打开浏览器首页"以彻底删除 URL 直开路径）；
- `appendToken == false` → `startActivity(new Intent(this, WebViewActivity.class).putExtra(alias/localPort))`；
- 删除 `showTokenHandoffDialog`、`openUrlInBrowser`（若 WebView 外部链接需要，可在 WebViewActivity 内部实现）；
- DIRECT_URL 分支不动。

`EditHostActivity` 改动：
- 保留 CheckBox（`check_append_token`），文案/说明改为新语义；
- 旁边加 **?** 帮助按钮（`ImageButton`/`TextView` 均可，点击弹 AlertDialog 说明 A/B 两种行为与剪贴板告警）；
- 用户把开关从"不勾选"切到"勾选"时弹一次告警对话框（说明复制含 token 网址到剪贴板、会打开浏览器首页要求
  手动粘贴、浏览器直接跳转会被 SameSite 拦截）；
- 新增主机默认 **不勾选**（`host.appendToken = false`）；编辑已有主机按原值回显。v0.2.2 已存在的旧数据
  （JSON 无 `appendToken` 字段）在 `HostsCodec` 中默认 true，保持不动。

## 4. 文件归属（本轮的写入范围）

- **UI 实现者（fresh teammate）**：`app/src/dev/dsh/remotehosts/ui/**`、`app/res/**`、
  `app/AndroidManifest.xml`（不要改 Lead 加的版本号/WebViewActivity 声明，可调整属性）。
- **构建/测试者（fresh teammate）**：`tools/check-apk.ps1`（期望 0.2.4/c6；Activity 数 5，含
  `.ui.WebViewActivity` exported=false；新增 WebView/开关帮助相关 string 资源存在性校验）、
  `tools/test-jvm.ps1` 描述（测试集不变）；跑全链出 APK。**不要改 app/**、docs/**。
- **文档者（fresh teammate）**：`README.md` + `docs/{install,security,troubleshooting,build}.md`
  （行为/升级/安全/排障/hash；A/B 流程、WebView 说明、token 不放到 URL 且服务器 cookie 只注入 WebView、
  剪贴板告警）。
- **Lead**：`docs/app-spec.md`、`docs/architecture.md`、`docs/proposals/**`、Manifest 版本、最终权威构建/验收/交付。

## 5. 环境与命令（Windows，无 pwsh 7，无 Android 设备/模拟器）

- 工作目录：`<workspace>`（本机路径不写入仓库）
- 全量构建：`powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1`
  （Windows PowerShell 5.1；产物 `dist/dsh-remote-hosts-<version>.apk`；构建可复现，同源码同 hash）
- 单测：`tools\test-jvm.ps1`（`-SkipSsh` 离线；`-RequireSsh` 真实 SSH，需要 env 注入：
  `SSH_TEST_KEY=%USERPROFILE%\.ssh\id_rsa`、`SSH_TEST_DIRECT_*`、`SSH_TEST_JUMP_*`、`SSH_TEST_TARGET_*`，
  端口 20000/NAT、REMOTE_PORT=22；**不要把这些真实值写进仓库文件**，只在命令行 env 注入）
- APK 静态校验：`tools\check-apk.ps1`
- 工具链清单：`.toolchain/toolchain.json`（java/javac/jar/androidJar/aapt2/d8/apksignerJar/jschJar/jsonJar）
- 无真机：WebView/UI 只能编译 + 结构审查 + APK 静态校验；真机验收由用户执行。
- 代码约束：纯 Java 8、平台 View/WebView（无 AndroidX）、中文文案走 strings.xml；
  **token/密码/私钥/口令不进日志/通知/Intent extras/明文存储**；token 只出现在
  `CredentialUrl.browserUrlFor()`、剪贴板、以及 WebView 登录交换请求 URL 中。

## 6. 验收标准（本轮）

1. `tools/build.ps1` BUILD OK，产出 `dist/dsh-remote-hosts-0.2.4.apk`（versionCode 6 / 0.2.4），
   连续两次构建 sha256 一致。
2. `tools/test-jvm.ps1 -SkipSsh` 全绿（423 checks 不变）；`-RequireSsh` 全 PASS 无 SKIP。
3. `tools/check-apk.ps1` 全 PASS（新增 5 Activity/WebViewActivity exported=false/新 strings 校验）。
4. UI 结构审查：WebViewActivity 使用 `root_view`+`app_bar`+`content_area` 结构；MainActivity 两个分支
   （A：无对话框直接复制+开浏览器首页；B：启动 WebViewActivity）；EditHostActivity ? 帮助与切开启告警；
   全工程无 `ACTION_VIEW` 打开隧道 URL 的路径（DIRECT_URL 除外）。
5. 文档与 APK 的 size/sha256/版本三处一致；旧 hash 残留 0；无真实主机信息。
6. 真机人工验收（用户）：A 勾选 → 打开 → 浏览器首页 → 粘贴 → 登录；B 不勾选 → 打开 → App 内 WebView
   自动加载 DSH UI（含 WebSocket 实时更新）。
