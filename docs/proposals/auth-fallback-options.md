# 方案分析：Android 外部浏览器打不开远端 DSH（`authentication required`）

> **状态：已采纳并已演进（v0.2.3 → v0.2.4 → v0.2.5）**
>
> * **v0.2.3**：确认根因是**外部 App 发起导航 + 303 redirect 触发 Android 浏览器的 SameSite=Strict 拦截**
>   （手动地址栏导航属用户发起，不受影响）。当版把「附加 token」模式的交接改为
>   **「复制完整 URL（原封不动）→ 打开浏览器首页 → 用户粘贴」** 的对话框流程。
> * **v0.2.4**：采用下述**方案 A（内置 WebView + App 代做登录 + CookieManager 注入）**，作为默认打开方式；
>   同时保留「复制 + 粘贴」的外部浏览器路径（模式 A/B），并删除 v0.2.3 的交接对话框。见
>   [docs/handoff-v024.md](../handoff-v024.md) 与 [docs/app-spec.md §7.5](../app-spec.md#75-webviewactivityv025默认内置页面--顶部横栏可收起)。
> * **v0.2.5**：把「用内置页面还是外部浏览器」从**每主机开关**升级为**全局设置**——
>   主页「更多」→「设置」→「通过外部浏览器连接」（默认关闭 = 内置 WebView）；每主机 `appendToken`
>   字段/开关删除。方案 A 的横栏也支持收起。行为以 [docs/app-spec.md §7.6/§7.7](../app-spec.md#76-全局设置-coresettingsstorev025) 为准。
> * 方案 A/B/C/D/E 的原始分析与对比保留在下面，作为**历史决策记录**；当前实现对应方案 A（内置 WebView），
>   B（本地反代）仍未实现，作为 roadmap。

> 说明：以下正文为 v0.2.2 时期（方案提出时）的原始分析，版本背景与「推荐」章节保留当时的判断，
> 不代表 v0.2.5 的当前行为。

## 1. 结论先行

**这不是 App 没有把 token 放进网址。** 我们已实测：

- 远端 `dsh web` 的登录握手：`GET /?token=…` → **303 + `Set-Cookie: dsh-auth-…; SameSite=Strict`**
  → 跳转 `./`；只有带上该 cookie 的请求才返回 UI，否则 401 + `authentication required`。
- App 的 token 预校验（v0.2.1）在隧道内用同一个 URL 请求，**返回 303（token 有效）**——否则 App 会
  直接报"远端拒绝了 token"，不会打开浏览器。
- 因此问题在**浏览器侧没有把刚设置的 cookie 发回**：Android 浏览器把"由另一个 App 发起的导航 +
  redirect 链"判定为 cross-site，`SameSite=Strict` 的 cookie 在 303 之后被阻止发送；关闭"附加 token"
  后根本没有 cookie，同样 401。

这是 Android 浏览器的已知行为，Chrome / Firefox / Edge 都受影响（不是 Edge 独有）：
Stack Overflow「Why is a SameSite=Strict cookie for http://127.0.0.1:12345/ not being sent in Android
browsers?」（问题 75253898，回答 75332128）记录了 2023-01 起 Chrome 109 / Firefox 109 / Edge 109
同时出现该问题；DevTools 提示 cookie 因 `SameSite=Strict` + 来自其他站点的导航被阻止；他们验证过的
workaround 是**把服务端的"设置 cookie 的响应"从 302 改成 200 + `<meta http-equiv="refresh">`**。
另见 Chromium issue 1221316（SameSite context type should consider redirect chain）。
→ **在"外部浏览器 + 未修改的远端 dsh web"这个组合里，靠 App 侧拼 URL 无法修复。**

因此可选方案都围绕一件事：**绕开浏览器的 SameSite/redirect 限制**。

## 2. 可选方案

### 方案 A（推荐）：App 内置 WebView + App 代做登录 + CookieManager 注入

做法：新增一个 `WebViewActivity`，加载 `http://127.0.0.1:<隧道端口>/`（**网址不含 token**）。
加载前 App 自己通过隧道用 `HttpURLConnection` 完成一次 `GET /?token=…`，拿到 `Set-Cookie` 的
`name=value`，用 `CookieManager.getInstance().setCookie("http://127.0.0.1:<port>/", cookie + "; Path=/; SameSite=Lax")`
注入，然后再让 WebView 打开干净 URL。WebView 内没有"外部 App 发起 + 303 redirect"的判定问题，
cookie 由 App 直接设置，可控。

| 维度 | 评估 |
|---|---|
| 体积/依赖 | **复用 Android 系统 WebView 组件**（不是打包 Chromium；系统 WebView 由设备/Play 更新），**无第三方依赖**；代码约 250–350 行 + 1 个 layout；APK 预计 +10 KB 左右 |
| 是否保留 Edge | 不保留默认浏览器，但可保留「用浏览器打开（含 token）」按钮作为备选 |
| 安全 | 更好：浏览器 URL **不含 token**；token 只在 App→隧道一次请求里；WebView 需关闭 file/content access、只允许 loopback 导航、外链交给系统浏览器 |
| 可靠性 | 高（cookie 由 App 控制，绕开浏览器策略）；风险点是 WebView 对 DSH Web UI 的兼容性（Vite 前端 + WebSocket），现代 WebView 一般没问题，需真机确认 |
| 工作量 | 小（约 1–2 小时实现 + 构建/测试链） |
| 缺点 | 不是"系统浏览器"体验（无地址栏/扩展）；WebView 版本由系统决定（Android 8+ 均可，可在 Play 更新）；下载/通知等浏览器功能受限 |

> 回答你的问题："内置 WebView 开销大吗？"——**不大**。WebView 是 Android 系统组件（类似系统浏览器内核），
> 不需要把浏览器打进 APK；代码量约 250–350 行、APK 增量约 10 KB，比再加一个第三方库小得多。
> 若你的手机 Android System WebView 版本很老（可更新），才可能有兼容问题。

### 方案 B：本地反向代理自动注入 Cookie（保留默认浏览器 Edge）

做法：App 在隧道之上再起一个 loopback HTTP 反向代理（约 400–600 行，纯 `java.net`）：
代理先完成 token 交换，之后对**每一个**浏览器请求自动加 `Cookie:` 头转发给隧道；浏览器看到的是
`http://127.0.0.1:<proxyPort>/`，**URL 不含 token、浏览器也不需要存 cookie**。WebSocket upgrade
也需透传（`/api/remote.mux`），实现与测试成本最高。

| 维度 | 评估 |
|---|---|
| 体积/依赖 | 无第三方依赖；APK +30–50 KB；代码 400–600 行 |
| 是否保留 Edge | ✅ 完全保留（可设默认体验不变） |
| 安全 | 最好：URL 无 token，cookie 只在 App 进程内 |
| 可靠性 | 高（与浏览器无关），但手写 HTTP/WS 代理的边界（chunked/SSE/大文件/重连/Host 重写）是风险；可在桌面 JVM 用真实 dsh web 做部分自动化测试，WebSocket 需真机验证 |
| 工作量 | 大（半天到一天） |
| 缺点 | 实现复杂、回归面大；代理是"本机免认证网关"（只绑 loopback，属可接受信任边界） |

### 方案 C（最小成本试水）：本地"中转页"绕开 redirect chain

做法：App 起一个极小的 loopback HTTP 服务（约 120–200 行），浏览器打开它的页面；该页返回
**200 + `<meta http-equiv="refresh" content="0; url=http://127.0.0.1:<隧道端口>/?token=…">`**。
这样导航链由"同站页面"发起，而不是"外部 App 发起 + 303"，按 SO 的结论 SameSite=Strict 的 cookie
可能被正常发送。保留 Edge、代码量小。

| 维度 | 评估 |
|---|---|
| 体积/依赖 | 无；+8 KB 左右；150–200 行 |
| 是否保留 Edge | ✅ |
| 安全 | token 仍会短暂出现在浏览器 URL/历史（与现状相同） |
| 可靠性 | **不确定**：依赖浏览器对 redirect chain/same-site 的具体启发式；SO 的 workaround 是在**同一 origin**上把 302 改 200+meta refresh，跨端口 helper 页是否同样生效需要真机验证 |
| 工作量 | 小（约 1 小时） |
| 失败后 | 再上 A 或 B，代码可弃 |

### 方案 D：远端侧 cookie 改写（App 零改动）

做法：在远端 `dsh web` 前跑一个几十行的反代（Node/Python），把 `Set-Cookie` 里的
`SameSite=Strict` 改写为 `SameSite=Lax`（或删除）后转发；App 的 TUNNEL 端口指向该反代端口，
浏览器即可正常接收/发送 cookie。SO 回答已验证"把 SameSite 改 Lax 后一切正常"。

| 维度 | 评估 |
|---|---|
| 体积/依赖 | App 零改动；远端+`node` 脚本（用户已有 dsh 环境） |
| 是否保留 Edge | ✅ |
| 可靠性 | 高（直接消除浏览器拒绝条件） |
| 缺点 | 需要远端常驻/运维（重启、端口、systemd/screen）；安全语义变化（cookie 从 Strict→Lax，但 cookie 只经 SSH 隧道到手机回环，暴露面不变） |
| 工作量 | 远端 30–50 行脚本；App 不改 |

### 方案 E：上游修复 DSH 登录握手（最根本，但不归我们控制）

把 `GET /?token=` 的 303 改为 200 + meta refresh，或把 cookie 改为 `SameSite=Lax`。
需要 DSH 官方版本更新（可提 issue/PR），App 侧届时无需改动。周期不可控。

### 方案 F：仅诊断/使用方式增强（不解决根因）

- App 内加"诊断页/内置调试 WebView"，显示 token 预校验结果、实际 URL（masked）、复制含 token 网址；
- 打开失败时提示"Edge 的 cookie/隐私设置/无痕/HTTPS-only"可能原因；
- 增加"用其他浏览器打开"选择器（但 Chrome/Firefox 同样受影响，意义不大）。
代码量 ~100 行，不解决当前问题，但方便定位。**不建议单独采用。**

## 3. 对比表

| 方案 | 改动量 | APK 增量 | 保留 Edge | URL 含 token | 可靠性 | 主要风险 |
|---|---|---|---|---|---|---|
| **A. 内置 WebView + 注入 cookie** | 小（250–350 行） | ~10 KB（系统 WebView） | 可选保留 | ❌ 不含 | 高 | DSH UI 在 WebView 的兼容性需真机确认 |
| **B. 本地反向代理注入 cookie** | 大（400–600 行） | 30–50 KB | ✅ | ❌ 不含 | 高 | 手写 HTTP/WS 代理的边界正确性 |
| **C. 本地中转页 + meta refresh** | 小（150–200 行） | ~8 KB | ✅ | ✅ 短暂出现 | 中/不确定 | 依赖浏览器 SameSite 启发式，需真机试 |
| **D. 远端 cookie 改写反代** | App 0 / 远端 30–50 行 | 0 | ✅ | ✅ 短暂出现 | 高 | 需远端常驻运维 |
| **E. 上游修 DSH** | 上游 | 0 | ✅ | 可能不含 | 高 | 周期不可控 |
| F. 仅诊断增强 | 小 | ~5 KB | ✅ | 不变 | 不解决 | — |

补充：换浏览器（Chrome/Firefox）**不能解决**——SO 记录 Chrome 109 / Firefox 109 / Edge 109 同时受影响；
老版本 Opera 例外但不可取。

## 4. 推荐

1. **首选 A（内置 WebView + App 注入 cookie）**：改动小、不打包浏览器、直接绕开浏览器 cookie 策略，
   同时让 URL 不再含 token（安全更好）；保留「用浏览器打开」按钮作为退路。
2. 如果你**必须保留 Edge 默认浏览器**：
   - 先花 ~1 小时上 **C**（最小改动试投；成功即保留 Edge）
   - C 若仍 401 → 上 **B**（可靠但工作量大），或接受 **D**（远端加一个小反代，App 零改动）
3. 如果愿意在远端做一次性配置，**D** 是"保留 Edge + 改动最少（App 侧）"的现实选择。
4. **E** 可以并行：在 DSH 上游提 issue（303+SameSite=Strict 在 Android 外部浏览器不可用），
   长期由官方修。

## 5. 选型后我会怎么做（以 A 为例，历史计划）

> 以下为 v0.2.3 时期的落地计划，**已按方案 A 在 v0.2.4 实现**；其中第 3 条的“主机级开关”在
> **v0.2.5 已升级为全局设置**（见文首状态说明），不再作为当前设计。

1. 新增 `ui/WebViewActivity`（系统 `android.webkit.WebView`，无新依赖）+ layout + Manifest；
   设置：JS 开、DOM storage 开、关闭 file/content access、只允许 loopback 导航、外链交给系统浏览器、
   返回键处理、加载进度与错误页；
2. 扩展 core：`TokenExchange`（在 TokenProbe 基础上捕获 `Set-Cookie`，纯 Java 可测），
   把 cookie 注入 `CookieManager` 后加载 `http://127.0.0.1:<port>/`；
3. ~~主机级开关「在 App 内打开」/「用浏览器打开」（默认 App 内，可切回浏览器并保留复制网址）~~
   → **v0.2.5 已改为设置页全局开关「通过外部浏览器连接」**（默认关闭 = 内置 WebView）；
4. 版本 0.2.3（versionCode 5）为当时的交付计划；实际 v0.2.4（code 6）引入 A/B，v0.2.5（code 7）
   完成全局设置与横栏收起。构建/测试/验收数字见 [docs/build.md](../build.md) 与
   [docs/install.md §9](../install.md#9-真机首次验收清单可选但建议做一遍)。
