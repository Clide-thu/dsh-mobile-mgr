# v0.2.5 交接文档（Settings 全局开关 + WebView 顶部横栏收起）

> **状态：已交付 / 已验收（自动化部分）**。构建产物已产出并通过静态检查、JVM 单测与真实 SSH 回归；
> 真机 UI 验收（尤其横栏收起）仍需用户执行，清单见 §9。
>
> 本文件是 v0.2.5 这一轮的交接与交付记录。所有结论以仓库源码和 `docs/app-spec.md` 为准；
> 本文件只用于让新加入的子智能体/维护者快速获得上下文，不替代正式文档。

## 0. 交付状态（v0.2.5）

- 版本：**versionName 0.2.5 / versionCode 7**（`app/AndroidManifest.xml` 已改）。
- 产物：`dist/dsh-remote-hosts-0.2.5.apk`，**751845 字节（约 0.72 MB）**，
  SHA256 `b6c613b682a093d0aea02edf987fd5a2d2de23a1f1a16cbd53f55d511434a1d9`。
- **可复现**：同一源码两次独立构建（含 `-Clean`）得到完全相同的 SHA256；构建耗时约 **22 秒**。
- 验收：`tools/check-apk.ps1` **76/76 PASS**；JVM 离线单测 **415 checks** 全 PASS
  （CredentialUrl 59、SshConfigParser 62、HostsCodec 85、LogSanitizer 36、SshKeyManager 86、
  HostsMigration 60、TokenProbe 27）；真实 SSH（SshSmokeTest + TunnelEngineTest，`-RequireSsh`）PASS 且无 SKIP。
- 升级：同一 keystore（`keystore/release.jks`，alias `dshremote`）覆盖安装，**0.2.4 → 0.2.5 设备数据保留**；
  旧每主机开关设置被丢弃，全局默认 = 内置 WebView。
- Manifest：**6 个 Activity**（MainActivity、EditHostActivity、ImportConfigActivity、SshKeysActivity、
  **SettingsActivity 新增**、WebViewActivity）；`TunnelService` `exported=false` +
  `foregroundServiceType=dataSync`；`allowBackup=false`；minSdk 26 / targetSdk 35。
- 仍待用户确认：真机上的设置页开关、内置页面横栏自动/手动收起与展开、错误面板自动展开等 UI 行为（§9）。

### 0.1 仓库基线（v0.2.4 已冻结，v0.2.5 在其上增量）

- 纯 Java 8 原生 Android（无 Gradle、无 AndroidX、无第三方 Android 依赖），平台 View +
  系统 `android.webkit.WebView`；包名 `dev.dsh.remotehosts`，minSdk 26，targetSdk 35。
- 零 Gradle 构建：`powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1`
  （aapt2 → javac → jar → d8 → package → zipalign → apksigner；输出 `dist/dsh-remote-hosts-<ver>.apk`）。
- 单测：`tools/test-jvm.ps1`（7 组纯 JVM 测试 + 真实 SSH 冒烟，`-SkipSsh` 只跑离线）。
- APK 静态验收：`tools/check-apk.ps1`（v0.2.5 期望 versionName 0.2.5 / versionCode 7）。
- 主入口：`app/src/dev/dsh/remotehosts/ui/MainActivity.java`；内置 WebView 页：
  `ui/WebViewActivity.java`；主机编辑：`ui/EditHostActivity.java`；v0.2.5 设置页：`ui/SettingsActivity.java`。
- 所有 Activity 的布局契约（v0.2.0 修复顶栏遮挡问题）：
  root 为竖向 `LinearLayout` + `android:id="@+id/root_view"` + `fitsSystemWindows`；
  第一个子视图是 `include layout="@layout/view_app_bar"`（id `app_bar`）；
  内容区 id `content_area`、`height=0dp`、`weight=1`；`UiInsets.apply(this)` 在运行时补
  状态栏/导航栏 inset。**任何新页面必须沿用此契约**，否则会重新出现顶部遮挡。
- v0.2.5 起 `UiInsets` 额外支持 app_bar 的 plain tag（动态基准高度）：WebViewActivity 收起/展开
  横栏时写入 `setBarBaseHeight()` 并 `requestApplyInsets()`；**其他页面没有 tag，行为不变**。

## 1. 用户本轮需求（原文要点）

1. 内置 WebView 打开页面后，顶部横栏可自动/手动收起，最大化可视区域。
   - 若完全收起，如何呼出？呼出按钮会不会侵占 WebView 区域？
   - 若不能完全收起：现有横栏右侧加收起按钮；收起后只显示一行较小的主机名；
     再点按收起后的横栏可展开。
2. 主页「更多」菜单新增「设置」子页面。
3. 把「是否开启横栏收起功能」做成设置页里的开关。
4. 把每个主机的「打开时在网址中附加 token」开关及说明移到设置页，变成**全局**开关，
   名称改为「通过外部浏览器连接」。

## 2. 本轮决策（已全部实现）

- **不做完全隐藏**：完全隐藏需要一个始终悬浮的「呼出」按钮，会覆盖 WebView 内容或挡住
  页面按钮。采用用户给的第 2 方案：收起后保留一行约 32dp 的主机名（仍属于顶栏，不覆盖
  content_area），点按即展开。展开 56dp → 收起 32dp（含状态栏 inset 之外）。
- 设置页里「横栏收起」开关默认 **开启**；关闭后内置页面无收起按钮、不自动收起。
- 自动收起规则（仅在开关开启时）：WebView 内容向下滚动超过约 12dp 且已离开顶部时自动
  收起；滚回页面顶部（scrollY≈0）时自动展开；右侧按钮可随时手动收起；点按收起后的
  横栏展开。向上滚动**不**自动展开（避免与手动收起打架）。
- 全局「通过外部浏览器连接」开关默认 **关闭**（= 内置 WebView 全自动，推荐）。
  开启 = v0.2.4 的 A 路径（复制含 token 的网址到敏感剪贴板 + 打开浏览器首页，不询问，
  告警/说明在设置页开关处：? 帮助 + 开启时一次性告警）。
- 每个主机的 `appendToken` 字段**彻底删除**（模型、JSON、编辑页、服务预校验条件）。
  旧版加密存储里多余的 `appendToken` JSON 字段由 org.json 忽略，无需迁移。
  旧主机升级后统一走全局开关（默认内置 WebView）。
- 设置持久化：新增 `core/SettingsStore`（Android `SharedPreferences`，文件名 `settings`），
  只存非敏感布尔值；token/密码/私钥绝不进 prefs。
- WebView 顶部栏：沿用现有 `view_app_bar.xml`，在标题组右侧、`appbar_more` 左侧新增
  `appbar_collapse` ImageButton（默认 gone，仅 WebViewActivity 在开关开启时显示）。
  收起时：隐藏返回键/副标题/收起按钮，标题改为 13sp 常规字重的主机名（别名），
  横栏高度 32dp + 状态栏 inset；点按横栏展开。展开时恢复返回键、18sp 粗体标题
  `@string/webview_title`、副标题=别名、收起按钮。
- 动态改变横栏高度与 `UiInsets` 的兼容：`UiInsets.applyTopInset()` 改为从 View 的
  **plain tag**（`setTag(Integer)`）读取当前基准高度（无 tag 时用 apply 时捕获的高度），
  新增 `UiInsets.setBarBaseHeight(View, int)`；WebViewActivity 收起/展开后调用
  `root.requestApplyInsets()`。其他页面的 app_bar 没有 tag，行为不变。
- 版本：v0.2.5 / versionCode 7。`tools/check-apk.ps1` 默认期望值同步。

## 3. 文件所有权（本轮写作用范围，均已完成）

### Lead（已完成）
- `app/res/values/strings.xml`（所有 v0.2.5 文案，**其他人不要改**）
- `app/AndroidManifest.xml`（版本 + SettingsActivity 声明）
- `app/res/menu/menu_main.xml`（新增 `menu_settings`）
- `app/src/dev/dsh/remotehosts/core/SettingsStore.java`（新）
- `app/src/dev/dsh/remotehosts/core/HostConfig.java`（删 appendToken）
- `app/src/dev/dsh/remotehosts/core/HostsCodec.java`（删 appendToken 编解码）
- `app/src/dev/dsh/remotehosts/core/TunnelService.java`（token 预校验改为读全局设置）
- `app/src/dev/dsh/remotehosts/ui/MainActivity.java`（菜单入口 + 全局打开方式）
- `app/src/dev/dsh/remotehosts/ui/SettingsActivity.java` + `app/res/layout/activity_settings.xml`（新）
- `app/src/dev/dsh/remotehosts/ui/EditHostActivity.java` + `app/res/layout/activity_edit_host.xml`
  （删除每主机开关，改为设置页提示）

### teammate `webview-collapse`（已完成，只改这些）
- `app/src/dev/dsh/remotehosts/ui/WebViewActivity.java`
- `app/res/layout/activity_webview.xml`
- `app/res/layout/view_app_bar.xml`
- `app/src/dev/dsh/remotehosts/ui/AppBar.java`
- `app/src/dev/dsh/remotehosts/ui/UiInsets.java`
- `app/res/drawable/ic_collapse.xml`（新建，白色向上箭头 vector，参考 `ic_arrow_back.xml`）

### teammate `test-accept`（已完成，只改这些）
- `test/jvm/HostsCodecTest.java`（删除 appendToken 用例，补旧 JSON 容忍用例）
- `tools/check-apk.ps1`（v0.2.5 期望值 / 活动列表 / 资源检查）

### teammate `docs-v025`（已完成，只改这些）
- `README.md`、`docs/*.md`（app-spec / architecture / install / security / troubleshooting /
  build / proposals）、`docs/handoff-v025.md` 可更新

## 4. 新增资源契约（已由 Lead 写入 strings.xml）

设置页 / 菜单：
- `menu_settings`(设置), `settings_title`(设置), `settings_section_connection`,
  `settings_section_display`, `settings_browser_mode_label`(通过外部浏览器连接),
  `settings_browser_mode_hint`, `settings_browser_mode_help_title`,
  `settings_browser_mode_help_message`, `settings_browser_mode_warn_title`,
  `settings_browser_mode_warn_message`, `settings_collapse_label`,
  `settings_collapse_hint`, `settings_collapse_help_title`,
  `settings_collapse_help_message`, `settings_note`, `help_symbol`("?"), `help_desc`
- `open_mode_settings_hint`（编辑页里替代原每主机开关的提示）
- `webview_collapse`（收起按钮 contentDescription）
- 保留：`append_token_copied_hint`、`append_token_browser_failed`、
  `append_token_copy_failed`（A 路径 Toast）、`menu_copy_open_url` 等长按复制 URL 文案。
- 删除：`append_token_label`、`append_token_hint`、`append_token_help_*`、
  `append_token_warn_*`（旧每主机开关文案）。

布局 id：
- `activity_settings.xml`：`switch_browser_mode`(Switch)、`switch_collapse_app_bar`(Switch)、
  `btn_browser_mode_help`(TextView "?" 44dp)、`btn_collapse_help`、`content_area`、`root_view`
- `view_app_bar.xml` 新增：`appbar_collapse`(ImageButton 44dp, src=`@drawable/ic_collapse`,
  contentDescription=`@string/webview_collapse`, visibility=gone)
- `activity_webview.xml` 可加 `webview` 相关新 id，但保持 root/app_bar/content_area 契约

## 5. SettingsStore API（Lead 已实现，其他角色按此调用）

```java
package dev.dsh.remotehosts.core;
public final class SettingsStore {
    // 文件名 settings，仅非敏感布尔值
    public static boolean isBrowserMode(Context context);      // 默认 false
    public static void setBrowserMode(Context context, boolean enabled);
    public static boolean isCollapseAppBar(Context context);   // 默认 true
    public static void setCollapseAppBar(Context context, boolean enabled);
}
```

## 6. 主流程（v0.2.5 后）

```
MainActivity 打开主机 → TunnelService 建隧道
  ├─ SettingsStore.isBrowserMode == true  → 复制含 token URL 到敏感剪贴板 + 打开浏览器首页
  └─ SettingsStore.isBrowserMode == false → WebViewActivity(alias, localPort)
        ├─ 读 HostStore 取 token
        ├─ GET /?token=…（不跟随 303）拿 dsh-auth-* cookie
        ├─ CookieManager 注入 + flush
        ├─ WebView 加载 http://127.0.0.1:<port>/（无 token）
        └─ 顶部栏可收起（受 isCollapseAppBar 控制）
```

TunnelService 的 token 预校验（TokenProbe）只在 `isBrowserMode == true` 时执行；
内置 WebView 模式由 WebViewActivity 自己交换 token 并显示错误面板（带「去编辑凭证/重试」）。

## 7. 构建/测试命令（已执行；`build/` 不得并发使用）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1 -Clean
powershell -NoProfile -ExecutionPolicy Bypass -File tools\test-jvm.ps1 -SkipSsh
# 真实 SSH（环境变量由 Lead 提供，不写入文件）：
powershell -NoProfile -ExecutionPolicy Bypass -File tools\test-jvm.ps1 -RequireSsh
powershell -NoProfile -ExecutionPolicy Bypass -File tools\check-apk.ps1
```
`build.ps1` 与 `test-jvm.ps1` 都使用 `build/`，**不得并发执行**。
本轮实际结果：离线 **415 checks** PASS；`-RequireSsh` PASS 且无 SKIP；`check-apk` **76/76 PASS**；
两次构建 SHA256 一致（`b6c613b6…34a1d9`）。验收日志保存在 `build/lead-accept-v025/`。

## 8. 安全不变量（不得破坏）

- token 只出现在：`CredentialUrl.browserUrlFor()` 的返回值、外部浏览器模式的敏感剪贴板、
  WebViewActivity 内部一次性交换请求、`HostsCodec` 加密文档。
- token 不写入日志/通知/Intent extras/SharedPreferences/普通文件；WebView 最终 URL 不含 token。
- WebView 保持：关闭 file/content access、禁止 mixed content、禁止调试、外部 http(s) 链接
  交给系统浏览器、只允许 `127.0.0.1:<localPort>` 在本 WebView 内导航。
- 设置页只存两个布尔值（SharedPreferences 文件 `settings`），不读取任何凭证。

## 9. 交付物与剩余验收项

### 9.1 交付文件（v0.2.5）

| 类别 | 文件 |
|---|---|
| 构建产物 | `dist/dsh-remote-hosts-0.2.5.apk`（751845 字节，SHA256 `b6c613b682a093d0aea02edf987fd5a2d2de23a1f1a16cbd53f55d511434a1d9`） |
| 版本/清单 | `app/AndroidManifest.xml`（0.2.5 / 7、SettingsActivity） |
| 新增源码 | `core/SettingsStore.java`、`ui/SettingsActivity.java`、`res/layout/activity_settings.xml`、`res/drawable/ic_collapse.xml` |
| 修改源码 | `core/HostConfig.java`、`core/HostsCodec.java`、`core/TunnelService.java`、`ui/MainActivity.java`、`ui/EditHostActivity.java`、`ui/WebViewActivity.java`、`ui/AppBar.java`、`ui/UiInsets.java`、`res/layout/view_app_bar.xml`、`res/layout/activity_webview.xml`、`res/values/strings.xml`、`res/menu/menu_main.xml` |
| 测试/校验 | `test/jvm/HostsCodecTest.java`、`tools/check-apk.ps1` |
| 文档 | `README.md`、`docs/app-spec.md`、`docs/architecture.md`、`docs/install.md`、`docs/security.md`、`docs/troubleshooting.md`、`docs/build.md`、`docs/proposals/auth-fallback-options.md`、本文件 |

### 9.2 真机验收清单（剩余，用户执行）

详见 [docs/install.md §9](install.md#9-真机首次验收清单可选但建议做一遍)。重点：

- [ ] 设置页可打开；两个开关默认值正确（外部浏览器关闭 / 横栏收起开启）、切换后重启仍保持。
- [ ] 默认内置页面：点「打开」不跳外部浏览器，WebView 自动登录并显示 DSH UI。
- [ ] 横栏：下滚自动收起为 32dp 一行主机名；滚回顶部自动展开；向上滚动（非顶部）不自动展开；
      点按横栏展开；右侧按钮手动收起；不遮挡 WebView 内容；错误面板出现时自动展开。
- [ ] 设置里关闭横栏收起后：始终 56dp、无收起按钮、不自动收起。
- [ ] 设置里开启「通过外部浏览器连接」：弹一次告警；点「打开」复制完整 token 网址 + 打开浏览器首页；
      地址栏粘贴后可登录；关闭开关后恢复内置页面。
- [ ] 升级：用同一签名 APK 覆盖安装，主机/凭证/密钥库/信任表保留；旧每主机开关不再出现。

### 9.3 已知边界

- 自动收起依赖系统 WebView 上报 `View.OnScrollChangeListener`；个别 ROM 不触发时仅自动收起失效，
  手动收起/点按展开仍可用（见 [docs/troubleshooting.md §16.3](troubleshooting.md#163-内置页面横栏不自动收起)）。
- 收起状态不持久化；完全隐藏横栏未做（需要悬浮呼出按钮，会遮挡内容）。
- 真机 UI 行为尚未由用户确认；自动化侧（编译、415 checks、真实 SSH、76/76 APK 校验）已通过。
