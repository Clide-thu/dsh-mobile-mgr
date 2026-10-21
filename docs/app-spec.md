# App 行为契约（实现与验收的唯一依据）

包名 `dev.dsh.remotehosts`；minSdk 26（Android 8.0），targetSdk 35；纯 Java 8 + 平台 View，
**禁止引入 AndroidX 或任何第三方 Android 依赖**（构建方式见 docs/build.md）。UI 文案中文。

## 1. 主机模型 `core/HostConfig`

| 字段 | 类型 | 说明 |
|---|---|---|
| `alias` | String | 唯一键（SSH config 的 Host 名）；非空 |
| `hostName` | String | SSH 主机名/IP；空则用 alias |
| `user` | String | SSH 用户 |
| `port` | int | SSH 端口，默认 22 |
| `authType` | enum `PASSWORD` / `PRIVATE_KEY` | SSH 认证方式 |
| `password` | String | 密码认证时使用，可空（连接时再要求） |
| `keyId` | String | **v0.2.0 起**：引用密钥库（§10）中的一对密钥；私钥认证首选此字段 |
| `privateKey` | String | 旧版内联私钥；**仅用于迁移**，加载时由 HostStore 迁入密钥库并清空 |
| `keyPassphrase` | String | 旧版内联私钥口令；迁移后清空 |
| `proxyJump` | String | 可选；`user@host:port` 或同列表中的 alias；空=直连 |
| `accessMode` | enum `TUNNEL` / `DIRECT_URL` | 默认 TUNNEL |
| `remotePort` | int | TUNNEL 模式：远端 `dsh web` 端口（来自凭证 URL） |
| `token` | String | TUNNEL 模式：远端启动 token（来自凭证 URL） |
| `directUrl` | String | DIRECT_URL 模式：直接打开的完整 URL |
| `identityFiles` | String[] | 从 SSH config 导入的 IdentityFile（仅展示/提示，手机不可读文件） |
| `importedFrom` | String | 可选，导入来源说明 |

> **v0.2.5**：字段 `appendToken` 已从模型/JSON/编辑页彻底删除（打开方式改为全局设置，见 §4、§7.6、§7.7）。
> 旧版加密存储中的该字段解码时被忽略，主机正常加载，无需数据迁移。

派生状态（UI 显示用）：

* `hasCredential()`：TUNNEL 模式要求 `remotePort>0 && token` 非空；DIRECT_URL 模式要求 `directUrl` 合法。
* `isComplete()`：alias/user/hostName 非空 + 认证材料按 authType 齐备 + `hasCredential()`。

## 2. 凭证解析 `core/CredentialUrl`（安全关键，唯一 URL 出口）

```java
static Parsed parse(String userInput)
  // 接受 dsh web 打印的 http(s)://host:port/?token=…
  // 必须含非空 token；port 缺省 http=80 / https=443
  // 返回 Parsed{ ok, remotePort, token, reason }；reason 面向用户、不得回显 token
static String browserUrlFor(int localPort, String token)
  // 严格返回 "http://127.0.0.1:" + localPort + "/?token=" + urlencode(token)
static String plainUrlFor(int localPort)
  // 严格返回 "http://127.0.0.1:" + localPort + "/"（不接受 token 参数）
  // 用途：内置 WebView 完成 token 交换并注入 cookie 后，加载这个不含 token 的干净地址
static String maskToken(String token)   // 前3…后3；长度<=6 → "******"
static boolean isValidDirectUrl(String url)  // http/https 且 host 非空
```

**不变量**：token 只允许出现在 `browserUrlFor` 的返回值中。禁止写日志、写通知、拼进
`Exception.getMessage()`、写进 SharedPreferences 明文。

## 3. SSH config 解析 `core/SshConfigParser`

对齐仓库上一级 `remote-hosts/lib/ssh-config.mjs` 的语义（用例见 `test/jvm/SshConfigParserTest.java`）：

1. 每行 `keyword value`（也接受 `keyword=value`）；`#` 注释（引号内不算）；大小写不敏感。
2. `Host` 行可含多个 pattern；匹配规则：至少一个正向 pattern 命中、且没有 `!` 取反 pattern 命中；
   `*`/`?` 通配，大小写不敏感。
3. 对同一个 alias，**首次取到的值生效**（文件顺序敏感），不是"后者覆盖前者"。
4. `Host` 块外的全局指令视为对所有主机生效的默认值。
5. `Include` 支持：`parse(text)` 只解析单文件，遇到 Include 记 warning（`included` 目标列表），
   另提供 `parse(Map<String,String> files)` 重载（key=相对路径/文件名）供以后多文件导入；
   v1 UI 只用单文件 + warning。
6. 列表只输出"字面量 alias"：跳过 `*`/`?`/`!` 开头的 pattern；保持文件顺序；去重。
7. 识别并解析 `HostName`、`User`、`Port`、`ProxyJump`、`IdentityFile`（可多次）。
8. `Match` 块：不建模；其后的指令不再归属前一个 Host（避免误解析）。
9. 输出 `ParseResult{ List<HostConfig> hosts; List<String> warnings; }`，warnings 用于 UI 提示。

## 4. 存储 `core/HostStore` + `core/SecretBox` + `core/HostsCodec`

* 文件：`context.getFilesDir()/hosts.enc`，内容 = AES-256-GCM 加密的 JSON。
* 密钥：Android Keystore 生成/保存 AES-256 密钥（alias `dsh-mobile-store`，不可导出）；
  每次写入随机 12 字节 IV，文件格式 `MAGIC(4) | VERSION(1) | IV(12) | ciphertext`。
* JSON 结构（`HostsCodec`，纯 Java 可 JVM 测试）：
  ```json
  { "version": 1,
    "hosts": [ { ...HostConfig 全字段... } ],
    "sshKeys": [ { "id": "...", "label": "...", "keyType": "ssh-rsa", "publicKey": "ssh-rsa AAAA… label",
                   "privateKey": "-----BEGIN …", "passphrase": "", "fingerprint": "SHA256:…",
                   "createdAt": "ISO", "source": "generated" } ],
    "trustedHostKeys": { "host.example.com:22": { "keyType": "ssh-rsa", "keyBase64": "...", "fingerprint": "SHA256:...", "addedAt": "ISO" } } }
  ```
* 主机列表/密钥列表/信任表都在同一个加密文件里，一次写入保证三者一致；旧版本 JSON（无 `sshKeys` 字段）
  必须能正常解码（缺省为空）。
* **v0.2.5 字段删除**：`HostsCodec` 不再编码/解码 `hosts[*].appendToken`。旧版加密文档里残留的该字段由
  `org.json` 在解码时**忽略**，主机其余字段正常加载——**不需要迁移**，也不需要改写旧文档。
* **v0.2.5 全局设置不在此文件**：两个非敏感布尔值放在 `core/SettingsStore`（SharedPreferences 文件
  `settings`），不与加密的凭证/密钥混存；详见 §7.6、§7.7。
* **迁移**：加载时把 `hosts[*].privateKey` 非空且 `keyId` 为空的旧主机，转成密钥库条目（按私钥内容去重），
  给主机写入 `keyId` 并清空 `privateKey`/`keyPassphrase`，迁移结果立即持久化；迁移失败不得丢主机数据。
* API（UI / Service 使用）：
  ```java
  HostStore.get(Context)                // 进程内单例
  List<HostConfig> list()               // 按添加顺序；已解密的内存对象
  HostConfig find(String alias)
  void upsert(HostConfig host)          // 存在则替换（保序）
  boolean remove(String alias)          // 同时删除该 alias 的隧道（由 Service 负责）
  String trustedHostKey(String hostPortKey)  // null = 未信任
  HostKeyRecord trustedHostKeyRecord(String hostPortKey)
  Map<String, HostKeyRecord> trustedHostKeyEntries()  // 保序快照，key = hostPortKey
  void trustHostKey(String hostPortKey, String keyType, String keyBase64, String fingerprint)
  int clearTrustedHostKeys()                     // 返回清除条数
  boolean clearTrustedHostKey(String hostPortKey)        // 单条清除
  boolean clearTrustedHostKeyFor(HostConfig host)        // 按主机清除（编辑页"重置密钥信任"）
  static String hostPortKeyFor(HostConfig host)          // hostName:port，与隧道引擎使用的键一致
  // v0.2.0 SSH 密钥库
  List<SshKeyRecord> listSshKeys()
  SshKeyRecord findSshKey(String id)              // 找不到返回 null
  void upsertSshKey(SshKeyRecord key) throws IOException   // 新增/按 id 替换
  boolean removeSshKey(String id) throws IOException       // 返回是否存在
  int countHostsUsingKey(String id)                        // 供 UI 删除前警告
  void reload() / void save() throws IOException
  ```
* 写盘必须原子（temp + rename）；失败要抛可读异常，UI 显示"保存失败"。
* 进程内缓存：Service 与 Activity 同进程；`HostStore` 单例即可，无需跨进程。

## 5. 隧道引擎 `core/SshTunnelEngine`（纯 Java，JSch）

```java
final class SshTunnelEngine {
  SshTunnelEngine(Listener listener);   // onState/onHostKey/onLog 回调
  SshTunnelEngine(Listener listener, KeyResolver keyResolver);  // v0.2.0：按 host.keyId 取密钥库材料
  int ensureOpen(HostConfig host, HostKeyPolicy policy, int timeoutMs) throws AppError;
  boolean isOpen(String alias);
  void close(String alias);
  void closeAll();
  interface HostKeyPolicy { Decision check(String hostPortKey, String keyType, String keyBase64, String fingerprint); }
  interface KeyResolver { SshKeyRecord find(String keyId); }   // null = 不存在
  // Decision: TRUST / REJECT_ONCE / REJECT_AND_FAIL
}
```

密钥材料解析规则（v0.2.0）：

1. `authType == PRIVATE_KEY` 且 `host.keyId` 非空 → 用 `KeyResolver.find(keyId)` 的 `privateKey`+`passphrase`；
   若 resolver 返回 null → `NEEDS_CREDENTIALS`（"所选密钥已不存在，请重新选择"）。
2. `keyId` 为空但 `host.privateKey` 非空 → 兼容旧数据，直接用内联私钥（HostStore 正常加载时已完成迁移，
   此分支只是防御）。
3. 两者都空 → `NEEDS_CREDENTIALS`。

行为：

1. 校验 host 完整性；缺认证材料 → `NEEDS_CREDENTIALS`；缺 alias/host → `BAD_REQUEST`。
2. 解析跳板机（见 architecture.md §4）；目标 Session `user@hostName:port`。
3. JSch 配置：
   * `StrictHostKeyChecking` 不用 JSch 内置文件，改用自定义 `HostKeyRepository`（查 `HostStore` /
     policy 回调）；未信任 → 抛出 `HostKeyUnknown` 供上层发 `NEEDS_HOSTKEY` 事件，**不自动信任**。
   * 密码：`session.setPassword`；私钥：`jsch.addIdentity(name, privateKey.getBytes(UTF_8), null, passphrase)`。
   * `PreferredAuthentications` = `publickey,password,keyboard-interactive`（按 authType 优先）。
   * `ServerAliveInterval=15000`、`ServerAliveCountMax=3`、`ConnectTimeout` 按 timeoutMs。
4. 本地端口：从 20000–60000 中探测一个空闲端口（ServerSocket(0) 探测后立即释放，绑定失败重试 ≤3 次），
   断言端口 ≠ 19387。
5. `setPortForwardingL("127.0.0.1", localPort, "127.0.0.1", remotePort)`。
6. 就绪探测：`HTTP GET http://127.0.0.1:<localPort>/`，2s 超时，**任何 HTTP 状态码都算就绪**
   （未认证的 401 才是健康答案），连接被拒则 150ms 后重试直到 timeoutMs；连续失败 → 关 Session。
7. **DSH token 预校验（v0.2.1；v0.2.5 起跟随全局设置）**：隧道就绪后、广播 OPEN 前，
   仅当 `SettingsStore.isBrowserMode(context) == true`（设置页「通过外部浏览器连接」开启）且 `token` 非空时，
   `TunnelService` 经隧道做一次 `GET http://127.0.0.1:<localPort>/?token=<token>`（**不跟随 303**，
   token 只出现在这个请求 URL，不进日志/异常）：
   * 2xx / 3xx → 通过（token 可重复交换，已实测）；
   * 401 / 403 → `NEEDS_CREDENTIALS`，message = "远端拒绝了 token（可能已失效：dsh web 重启会换新
     token）。请在远端重新复制 dsh web 打印的 URL 更新凭证。"，关闭本次隧道并广播 FAILED，
     **不把用户带到浏览器的 401 页面**；
   * 连接异常/超时 → `UNREACHABLE`；
   * 其他 4xx/5xx → 视为通过（不阻断，交给浏览器）。
   * 内置 WebView 模式（全局开关关闭，默认）**不做**这个预校验：`WebViewActivity` 自己交换 token 并显示
     错误面板（带「去编辑凭证 / 重试 / 关闭」）。
8. Session 断开时回调 `onState(alias, CLOSED)` 并从表里移除，下次 ensureOpen 重连。
9. 异常分类（`AppError.kind`）：
   * `AuthFailException`/认证失败 → `NEEDS_CREDENTIALS`
   * `UnknownHostKey`/指纹不匹配 → `NEEDS_HOSTKEY` / `HOST_KEY_CHANGED`
   * 连接超时/拒绝/转发失败 → `UNREACHABLE`
   * 参数问题 → `BAD_REQUEST`
   * 其他 → `INTERNAL`（message 已脱敏）
10. 脱敏：`LogSanitizer` 将 token/password/私钥/passphrase 替换为 `[redacted]`；异常 message 出口前过滤。

## 6. 服务协议 `core/ServiceProtocol`（Activity ⇄ Service）

Intent action（`startForegroundService`）：

| Action | Extra | 语义 |
|---|---|---|
| `dev.dsh.remotehosts.action.OPEN` | `alias` | 确保该主机隧道；成功广播 OPEN |
| `...ACTION_CLOSE` | `alias` | 关闭该主机隧道 |
| `...ACTION_STOP_ALL` | — | 关闭全部并退出前台 |
| `...ACTION_TRUST_HOSTKEY` | `alias`,`hostPort`,`keyType`,`keyB64`,`fingerprint`,`remotePort` | 信任该主机密钥后重试连接 |

广播（`sendBroadcast`，显式包名 + `setPackage`，接收方 `RECEIVER_NOT_EXPORTED`）：

* `EVENT` = `dev.dsh.remotehosts.event.TUNNEL`
* extras：`state` ∈ `STARTING|OPEN|FAILED|CLOSED|NEEDS_HOSTKEY`、`alias`、`localPort`、
  `errorKind`、`errorMessage`、`fingerprint`、`hostPortKey`、`keyType`、`keyB64`。
* 每次 OPEN/FAILED 必须带 alias；FAILED 的 message 必须已脱敏。

Service 细节：通知渠道 `tunnel`（低优先级、常驻），标题"N 台主机已连接"，动作"全部断开"；
`startForeground` 用 `FOREGROUND_SERVICE_TYPE_DATA_SYNC`；Android 13+ 未授权通知时仍运行。

服务入口兼容性：

* OPEN / TRUST_HOSTKEY 由 UI 用 `startForegroundService` 启动 → 必须在 5s 内 `startForeground`。
* CLOSE / STOP_ALL 由 UI 用 `startService`（服务可能未运行）→ 无隧道时直接 `stopSelf`，不得触发
  "did not call startForeground"；仍有隧道时保持前台。
* 进程内状态快照供 Activity 恢复时查询（避免暂停期间漏广播）：
  ```java
  // TunnelService
  static Map<String, Integer> openTunnelsSnapshot(); // alias -> localPort，仅 OPEN 状态，线程安全快照
  static boolean isRunning();
  ```

## 7. UI 行为

**全局布局规则（v0.2.0 起，修 v0.1.0 的遮挡缺陷）**：所有 Activity 使用自绘顶栏（`layout/view_app_bar.xml`：
品牌色 56dp、左标题/返回箭头、右侧「更多」PopupMenu），根布局为纵向 `LinearLayout`：
顶栏 + 内容区（`layout_height=0dp` + `layout_weight=1`），**不使用平台 ActionBar**（主题
`Theme.Material.Light.NoActionBar`）。每个 Activity 根布局 `fitsSystemWindows=true`，并在
`onCreate` 用 `setOnApplyWindowInsetsListener` 把 status bar inset 加到顶栏 paddingTop、nav bar
inset 加到内容区 paddingBottom，保证 targetSdk 35 强 edge-to-edge 下**任何页面**的首项/底部按钮
都不被顶部栏、状态栏、导航栏遮挡。长文本（公钥/私钥/配置预览）可滚动或换行，不截断。

**v0.2.5 动态顶栏高度（仅 WebViewActivity）**：`view_app_bar.xml` 新增可选的 `appbar_collapse` 按钮；
`UiInsets` 通过 app_bar 的 **plain tag**（`View.setTag(Integer)`，值 = 不含状态栏 inset 的基准高度）读取当前
基准高度，并提供 `UiInsets.setBarBaseHeight(view, px)`。没有 tag 的其他页面沿用 `UiInsets.apply` 时捕获的高度，
**行为不变**；WebViewActivity 在展开/收起后更新高度并调用 `root.requestApplyInsets()`。

### 7.1 MainActivity（主机列表）

* 标题栏：应用名 + 菜单（导入 SSH 配置 / 添加主机 / SSH 密钥管理… / 已信任主机密钥… / **设置** / 全部断开 / 关于）。
* 空状态：说明文字 + "导入 SSH 配置"按钮 + "添加主机"按钮。
* 行（`item_host.xml`）：
  * 第一行：alias（粗体）+ 状态 chip（未配置凭证 / 已配置 / 连接中… / 已连接 127.0.0.1:port）
  * 第二行：`user@hostName:port`，若有 ProxyJump 追加 `（经 jump）`；DIRECT_URL 显示"直接网址"
  * 按钮：`打开`（主）、`编辑`；长按行 → 对话框（断开隧道 / 删除主机 / **复制打开网址（含 token）**，
  v0.2.2：仅隧道处于 OPEN 时可实际复制（未 OPEN 时点击提示"请先点『打开』建立隧道"），复制后提示
  粘贴到浏览器；token 复制带敏感剪贴板警告）。
* 点击「打开」：
  * `!hasCredential()` → 对话框"还没有 DSH 凭证，去配置？"→ 编辑页。
  * DIRECT_URL → 立即 `ACTION_VIEW(directUrl)`。
  * TUNNEL → 起服务 OPEN，行内显示"连接中…"，注册广播；收到 NEEDS_HOSTKEY → 安全身份确认对话框
    （显示 SHA256 指纹）→ 信任并重试 / 取消；收到 FAILED → 错误对话框（按 kind 给中文建议）+
    可点"去编辑"；25s 无响应 → 超时提示。
  * 收到 OPEN 后（v0.2.4 起**不再有任何"把隧道 URL 直接交给浏览器"的 ACTION_VIEW 路径**；
    v0.2.5 起由全局设置选择载体）：
    * `SettingsStore.isBrowserMode(context) == true`（设置页「通过外部浏览器连接」开启）→ **直接**（无对话框）：
      1) 把 `http://127.0.0.1:<localPort>/?token=<token>`（`CredentialUrl.browserUrlFor`，原封不动）
         写入敏感剪贴板（API 33+ 标记 `EXTRA_IS_SENSITIVE`）；
      2) 打开默认浏览器首页（`Intent.makeMainSelectorActivity(ACTION_MAIN, CATEGORY_APP_BROWSER)`；
         失败仅 Toast 提示，不再回退 `ACTION_VIEW`）；
      3) Toast："网址已复制，请在浏览器地址栏粘贴打开"。
      相关告警/说明在 **SettingsActivity 开启该开关时**弹出一次，并可通过开关旁 **?** 随时查看。
    * `SettingsStore.isBrowserMode(context) == false`（默认）→ 启动 **`ui/WebViewActivity`**
      （内置系统 WebView 全自动打开，详见 §7.5），Intent 只带 `alias` + `localPort`，**不带 token**。
      不再打开外部浏览器。
    * App **从不向浏览器传递 cookie**；token 预校验（§5.7）只在全局开关开启时于隧道内执行。
* DIRECT_URL 模式：仍用 `ACTION_VIEW(directUrl)`（外部网址本身就是该模式的目的）。
* 运行时申请 `POST_NOTIFICATIONS`（Android 13+），拒绝不阻断使用。
* 显示/隐藏 token：列表与对话框永远只显示 `maskToken`。

### 7.2 EditHostActivity（新增/编辑）

字段（ScrollView）：alias*、HostName、User*、Port、认证方式（Radio：密码/私钥）、密码（密码认证时）、
**密钥选择（私钥认证时：Spinner 列出密钥库中的密钥 + 指纹摘要；旁边"管理 SSH 密钥…"按钮跳到 §7.4）**、ProxyJump、
访问方式（Radio：SSH 隧道/直接网址）、凭证 URL（隧道模式；粘贴后实时 parse，错误 inline 提示）、
直接网址（DIRECT_URL 模式）。底部：保存 / 删除 / 取消。

* v0.2.0 起**不再从编辑页直接导入私钥**：私钥只存在于 §7.4 的密钥库中；编辑页只选择 `keyId`。
* v0.2.5：**删除**每主机的「打开时在网址中附加 token」CheckBox 及开关旁 **?**（`HostConfig.appendToken` 一并删除）。
  原位置只保留一行静态提示（`@string/open_mode_settings_hint`）：「打开方式（外部浏览器 / 内置页面）已改为全局设置：
  请在主页「更多 → 设置」中切换。」。打开方式改由 §7.7 的全局开关决定；升级后旧主机的开关值被忽略、不做迁移。
  旧版加密存储里残留的 `appendToken` 字段在解码时被忽略（§4）。
* 密钥库为空且用户选择私钥认证时：显示"还没有 SSH 密钥"，提供"生成密钥 / 导入密钥对"入口。
* 密码输入框关闭自动填充（autofill）与保存态回填，避免输入法云同步。
* 保存时用 `CredentialUrl.parse` 校验；TUNNEL 模式只存 remotePort+token（不存原始 URL）。
* 提供"重置主机密钥信任"（仅编辑已存在主机时显示）。
* 保存成功返回列表并 Toast；删除需确认对话框。

### 7.3 ImportConfigActivity

多行粘贴框 + "选择文件"（`ACTION_OPEN_DOCUMENT`，`*/*` + `EXTRA_MIME_TYPES`（text/*、octet-stream），
因为 SSH config 常无扩展名；读取上限 1 MiB）+ "解析并导入"。
解析成功后显示：识别 N 台主机、跳过 M 个通配 pattern、W 条警告（Include 等）；列出前若干 alias
预览 → 确认后合并入 HostStore（已有 alias 保留凭证/认证）。解析失败显示错误、不落盘。

### 7.4 SshKeysActivity（v0.2.0：SSH 密钥管理）

* 入口：MainActivity 菜单「SSH 密钥管理…」；EditHostActivity「管理 SSH 密钥…」；空状态引导。
* 列表行：label（粗体）、类型（ssh-rsa/ssh-ed25519…）、指纹 `SHA256:…`（短形式）、
  「被 N 台主机使用」、创建时间；行点击 → 详情对话框。
* 顶部/空状态按钮：**生成新密钥**、**导入密钥对**。
* 生成对话框：label（默认 `app-key-<n>`）、RSA 位数单选（2048 / **3072 默认** / 4096）、
  说明"私钥只保存在本 App 的加密存储里；把公钥复制到远端 ~/.ssh/authorized_keys"。
  生成后立即展示公钥全文 + 「复制公钥」按钮。
* 导入对话框：label、私钥文本（多行粘贴 + SAF `*/*` 选文件，1 MiB 上限）、私钥口令（可选）、
  公钥文本（可选；提供则校验与私钥匹配，不提供则由私钥推导）。校验失败 inline 提示，不落盘。
* 详情对话框：label/类型/指纹/创建时间/来源/被哪些 host 使用；公钥全文（可选择复制）；
  私钥默认隐藏（`••••`），点「显示私钥」二次确认后显示，「复制私钥」给敏感剪贴板警告；
  「重命名」「删除」（被主机引用时警告：删除后这些主机将无法私钥登录）。
* 复制公钥/私钥用系统剪贴板；私钥复制 API 33+ 标记 `ClipDescription.EXTRA_IS_SENSITIVE`。
* 全流程不写日志；私钥/口令不进任何 Intent extra（对话框内直接调用 HostStore）。

### 7.5 WebViewActivity（v0.2.5：默认内置页面 + 顶部横栏可收起）

入口：MainActivity 收到 `STATE_OPEN` 且 `SettingsStore.isBrowserMode(context) == false`（默认）时启动；
Intent 只带 `alias` + `localPort`（**绝不带 token**），Activity 从 `HostStore` 读 token。

流程（后台线程完成后再 `loadUrl`；token 只在 App 内部请求 URL 中出现）：

1. `GET CredentialUrl.browserUrlFor(localPort, token)`（`HttpURLConnection`，不跟随 303，5s 超时）：
   * 2xx/3xx → 从响应头 `Set-Cookie` 找 `dsh-auth-*` 的 `name=value`（找不到 → 失败）；
   * 401/403 → 失败："远端拒绝了 token（可能已失效…）"，提供「去编辑凭证」/「关闭」；
   * IO/超时 → 失败："无法通过隧道读取远端 dsh web 响应"，提供「重试」/「关闭」。
2. `CookieManager`：`setAcceptCookie(true)`、`setAcceptThirdPartyCookies(webView, true)`、
   `setCookie("http://127.0.0.1:<localPort>/", pair + "; Path=/; SameSite=Lax; HttpOnly")`、`flush()`。
3. `webView.loadUrl(CredentialUrl.plainUrlFor(localPort))` —— **URL 不含 token，不经过外部浏览器**。
4. WebView 安全配置：JS/DOM storage 开；file/content access 关；
   `MixedContentMode=MIXED_CONTENT_NEVER_ALLOW`；只允许 `127.0.0.1:<localPort>` 在本 WebView 内导航，
   其他 http(s) 外链交给系统浏览器，其余 scheme 外部处理或忽略；主框架 `onReceivedError` 显示错误面板；
   `WebChromeClient` 处理 JS alert/confirm/prompt；返回键先 `canGoBack()`；`onDestroy` 移除并销毁 WebView。
5. 保留 §7 全局布局规则（root_view/app_bar/content_area + fitsSystemWindows + UiInsets），
   顶栏含返回 + 标题，内容区全屏 WebView + 顶部进度条 + 错误面板。
6. 不写日志 token/密码；不把 token/cookie 放进 Intent/通知。

**顶部横栏收起（v0.2.5，受 `SettingsStore.isCollapseAppBar` 门控）**

| 状态 | 高度 | 内容 |
|---|---|---|
| 展开 | **56dp**（不含状态栏 inset） | 返回箭头 + **18sp 粗体**标题 `webview_title` + 副标题（主机 alias）+ 右侧 `appbar_collapse`「收起」按钮 |
| 收起 | **32dp**（不含状态栏 inset） | 只显示一行 **13sp 常规字重**的主机名（alias）；整条横栏可点按展开；无返回箭头、无副标题、无收起按钮 |

* **不覆盖内容区**：收起态仍是 `content_area` 上方的独立横栏（32dp），不是悬浮控件，也不覆盖 WebView 内容。
  这是刻意取舍——完全隐藏需要一个始终悬浮的「呼出」按钮，会遮挡页面/页面按钮；保留一行可点按的主机名即可随时展开。
* **自动收起**：页面向下滚动超过约 **12dp**（`AUTO_COLLAPSE_SCROLL_DP`）后自动收起。
* **自动展开**：滚动回页面顶部（`scrollY ≤ 4dp` 且本次是向上滚动，`AUTO_EXPAND_SCROLL_DP`）时自动展开；
  **向上滚动但不在顶部时不自动展开**（避免与手动收起打架）。
* **手动**：右侧「收起」按钮可随时手动收起；收起态点按整条横栏展开。
* **错误面板**：`showError()` 会先展开横栏，保证错误说明不被 32dp 横栏挤压。
* **设置关闭时**：无收起按钮、不自动收起、始终 56dp（等同 v0.2.4 行为）。
* **返回逻辑不变**：硬件返回键/展开态返回箭头都是「WebView 历史优先，没有上一页再退出」；
  收起态没有返回箭头，可点按横栏展开后使用，或直接按系统返回键。
* **不持久化**：收起状态只存在于当前页面实例，每次打开都是展开态。
* **实现**：`view_app_bar.xml` 新增 `appbar_collapse`（ImageButton，默认 `gone`）；
  `applyBarBaseHeight()` 更新 `LayoutParams.height` 并通过 `UiInsets.setBarBaseHeight(appBar, px)` 写入 plain tag，
  再 `root.requestApplyInsets()`，使状态栏 inset 始终加在当前基准高度上（§7 全局布局规则）。
* **已知边界**：自动收起依赖系统 WebView 上报 `View.OnScrollChangeListener`；个别 ROM 若不触发该回调，
  自动收起不生效，但手动收起/点按展开仍可用。

### 7.6 全局设置 `core/SettingsStore`（v0.2.5）

```java
public final class SettingsStore {
    public static final String PREFS_NAME = "settings";       // SharedPreferences 文件名
    public static boolean isBrowserMode(Context context);      // 默认 false = 内置 WebView
    public static void setBrowserMode(Context context, boolean enabled);
    public static boolean isCollapseAppBar(Context context);   // 默认 true
    public static void setCollapseAppBar(Context context, boolean enabled);
}
```

* 只存两个**非敏感布尔值**（`browser_mode`、`collapse_app_bar`），文件 `settings` 位于 App 私有目录；
  **绝不**写入 token/密码/私钥/URL（凭证仍只在 `hosts.enc` 加密存储里）。
* 每次读取当前持久化值，因此开关修改后**下一次打开立即生效**，不需要重启 App。
* 升级兼容：缺失的键读取默认值（旧版没有该 prefs 文件）——`browser_mode=false`（内置 WebView）、
  `collapse_app_bar=true`；**旧每主机 `appendToken` 不迁移**。

### 7.7 SettingsActivity（v0.2.5，`activity_settings.xml`）

入口：MainActivity 菜单「设置」（`menu_settings`）。顶栏沿用 §7 布局契约（root_view/app_bar/content_area +
`UiInsets.apply`），标题「设置」，带返回箭头。

两个开关（加载时先 `setChecked` 再挂监听，避免回填触发一次性告警）：

| 开关 | 默认 | 立即持久化 | 行为 |
|---|---|---|---|
| **通过外部浏览器连接**（`switch_browser_mode`） | **关闭** | `SettingsStore.setBrowserMode` | 关闭 = 内置 WebView 全自动打开（推荐）；开启 = 隧道就绪后把含 token 的完整网址复制到敏感剪贴板 + 打开默认浏览器首页，用户手动粘贴。**开启时弹一次安全告警**；开关旁 **?**（`btn_browser_mode_help`）显示完整说明 |
| **自动收起内置页面的顶部横栏**（`switch_collapse_app_bar`） | **开启** | `SettingsStore.setCollapseAppBar` | 仅对内置 WebView 页面生效（§7.5）；关闭后横栏始终 56dp、无收起按钮、不自动收起。开关旁 **?**（`btn_collapse_help`）显示说明 |

* 页面文案分「连接与打开」「显示」两组，底部说明「设置立即生效并保存在本机；这里不保存任何密码、token 或密钥。」
* 开关变化与 `?` 帮助都只操作 prefs/对话框，**不读取任何凭证**；SharedPreferences 文件不加密但只含布尔值。
* 告警/帮助文案来自 `strings.xml` 的 `settings_browser_mode_warn_*` / `settings_browser_mode_help_*` /
  `settings_collapse_help_*`；编辑页的提示来自 `open_mode_settings_hint`。
* Manifest 中 `SettingsActivity` 为 `exported=false`。

## 8. SSH 密钥管理（v0.2.0，`core/SshKeyManager` + `core/SshKeyRecord`）

目标：App 自带一套**应用专用**的 SSH 密钥库，替代"每台主机各粘一份私钥"的做法，并让用户能
查看/生成/导入密钥对。密钥只保存在本 App 的加密存储（与主机凭证同一个 `hosts.enc`）。

### 8.1 模型 `SshKeyRecord`

| 字段 | 说明 |
|---|---|
| `id` | 唯一 id（`key-<12 hex>`，随机生成；创建后不变） |
| `label` | 用户可读名称（默认 `app-key-1`） |
| `keyType` | `ssh-rsa` / `ecdsa-sha2-nistp256` / `ssh-ed25519` … |
| `publicKey` | OpenSSH 公钥行全文：`<type> <base64> <comment>` |
| `privateKey` | 私钥全文（PEM 或 OpenSSH 格式；加密存储内） |
| `passphrase` | 私钥口令（可空；加密存储内） |
| `fingerprint` | `SHA256:<标准 base64(sha256(公钥 blob))>` 无 `=` 填充（与 `ssh-keygen -lf` 输出一致；勿用 base64url） |
| `createdAt` | ISO-8601 时间 |
| `source` | `generated` / `imported` / `migrated` |

### 8.2 API `SshKeyManager`（纯 Java，可 JVM 测试）

```java
static SshKeyRecord generate(String label, int rsaBits) throws AppError;  // 2048/3072/4096，默认 3072
static SshKeyRecord importKeyPair(String label, String privateKeyText, String passphrase,
                                  String publicKeyLineOrEmpty) throws AppError;
static String fingerprintOfPublicKey(String publicKeyLine) throws AppError;
static boolean looksLikePrivateKey(String text);       // 首行 -----BEGIN 判断
static String newId();                                 // key-<12 hex>
```

* 生成：JSch `KeyPair.genKeyPair(JSch, KeyPair.RSA, rsaBits)`；私钥用 `writePrivateKey(out)`
  序列化为**无口令 PEM**（生成 API 不设口令；静态保护由 Android Keystore 加密信封承担）；
  公钥行 = `getKeyTypeString() + " " + base64(getPublicKeyBlob()) + " " + label`。
* 导入：校验私钥可解析（含口令）；若给了公钥行则用公钥 blob 与私钥推导出的 blob 比对，不一致 →
  `BAD_REQUEST`（"公钥与私钥不匹配"）；口令错误 → `NEEDS_CREDENTIALS`（"私钥口令错误"）。
* 指纹：`SHA256:` + **标准 base64**（无 `=` 填充）的 SHA-256(公钥 blob)，与 `ssh-keygen -lf` 的
  SHA256 行一致（勿用 base64url）。
* **脱敏**：任何异常 message 不得包含私钥/口令内容（`LogSanitizer` 兜底）。

### 8.3 主机-密钥迁移

* 加载 `HostStore` 时，把 `privateKey` 非空且 `keyId` 空的旧主机迁移为密钥条目：
  相同私钥内容只建一条（按私钥文本去重），label 用首个主机 alias；主机写入 `keyId` 并清空
  `privateKey`/`keyPassphrase`，随后持久化。
* 迁移是纯 Java（`core/HostsMigration` 或 HostsCodec 内可测函数），JVM 单测覆盖：无旧数据、
  单主机、多主机共用同一私钥、已迁移幂等、迁移不改其他字段。

### 8.4 引擎与服务

* `SshTunnelEngine` 新增 `KeyResolver`（§5）；`TunnelService` 用 `HostStore::findSshKey` 实现。
* 通知/广播/日志仍不得出现私钥/口令；`LogSanitizer.secretsOf(host)` 之外，
  引擎在解析密钥库材料后把私钥与口令加入脱敏 secrets。

## 9. 错误分类与中文提示（UI 映射）

| kind | 提示 |
|---|---|
| `bad-request` | 配置不完整或格式错误（详见 message） |
| `unknown-host` | 主机不存在（可能已被删除） |
| `needs-credentials` | SSH 认证失败或缺少凭证——请检查密码/私钥/DSH 凭证 |
| `unreachable` | 连不上远端：网络、端口、跳板机或转发失败 |
| `host-key-changed` | 主机密钥已变更，可能被中间人攻击；确认后可在菜单清除信任 |
| `internal` | 内部错误（message 已脱敏） |

## 10. 验收清单

> **v0.2.5 自动化验收结果（已执行）**：`tools/check-apk.ps1` **76/76 PASS**；`tools/test-jvm.ps1 -SkipSsh`
> **415 checks** 全 PASS（CredentialUrl 59、SshConfigParser 62、HostsCodec 85、LogSanitizer 36、
> SshKeyManager 86、HostsMigration 60、TokenProbe 27）；`tools/test-jvm.ps1 -RequireSsh` 真实 SSH
> （SshSmokeTest + TunnelEngineTest）PASS 且**无 SKIP**；同一源码两次独立构建 APK **SHA256 一致**。
> 下列 `[ ]` 项为仍需真机人工确认的 UI/交互项。

- [x] `tools/build.ps1` 产出签名 APK（`dist/dsh-remote-hosts-0.2.5.apk`，751845 字节，SHA256
      `b6c613b682a093d0aea02edf987fd5a2d2de23a1f1a16cbd53f55d511434a1d9`），`apksigner verify` + `zipalign -c -v 4` 通过。
- [x] `tools/test-jvm.ps1 -SkipSsh` 全绿：SshConfigParser、CredentialUrl、HostsCodec、LogSanitizer、
      SshKeyManager（生成/导入/指纹/口令）、HostsMigration（旧内联私钥迁移）、TokenProbe，共 **415 checks**。
- [x] `SshSmokeTest` 对真实主机（直连 + ProxyJump）建隧道并读到目标 SSH banner。
- [x] `TunnelEngineTest` 覆盖 `KeyResolver` 路径（用导入的密钥库条目连接真实主机）。
- [x] `TokenProbeTest` 全绿（2xx/3xx→OK、401/403→REJECTED、IO/无监听→UNREACHABLE、其他 4xx/5xx→OK、
      不跟随 303、token 查询串编码往返）；真实 `dsh web` 三态已验证。
- [x] 打开流程（v0.2.1 起保留）：token 失效时 App 直接提示"远端拒绝了 token…"并关闭隧道、
      **不打开浏览器**（仅外部浏览器模式）；在编辑页粘贴最新凭证 URL 后重试可恢复。
- [x] v0.2.2 历史项：长按主机且隧道 OPEN 时可复制含 token 的完整网址（敏感剪贴板）。
      `appendToken` 已在 v0.2.5 删除，旧 JSON 中的该字段改为"解码时忽略、主机正常加载"的容错用例。
- [x] v0.2.3 / v0.2.4 历史项：token 交接对话框已在 v0.2.4 删除；A/B 两条路径的语义保留，
      但选择方式在 v0.2.5 升级为全局设置（见下一条）。
- [ ] **v0.2.5（设置页）**：主页「更多」→「设置」可打开；「通过外部浏览器连接」默认关闭、
      「自动收起内置页面的顶部横栏」默认开启；两个开关立即生效并持久化；开启外部浏览器时弹一次告警；
      两个 **?** 帮助可查看说明；SharedPreferences 文件只含布尔值。
- [ ] **v0.2.5（全局开关 + 删除字段）**：`BrowserMode=false`（默认）启动 `WebViewActivity`；
      `BrowserMode=true` 复制 token URL + 打开浏览器首页 + Toast；编辑页不再有每主机开关，只有设置提示；
      `HostConfig`/`HostsCodec` 无 `appendToken`，旧加密文档仍能正常解码并保留主机。
- [ ] **v0.2.5（横栏收起）**：展开 56dp（18sp 粗体标题 + 副标题 + 收起按钮）；下滚超过约 12dp 自动收起为
      32dp（13sp 常规字重主机名、整条可点按展开）；滚回顶部（≤4dp 且向上）自动展开；向上滚动不自动展开；
      右侧按钮可手动收起；错误面板出现时自动展开；设置关闭后始终 56dp 且无收起按钮；收起态不持久化。
- [x] APK 静态检查：minSdk 26 / targetSdk 35 / 6 个 Activity（含 `SettingsActivity`）/ `TunnelService`
      exported=false + `dataSync` / `allowBackup=false` 正确，无明文测试 token/私钥/真实主机信息（76/76 PASS）。
- [ ] 密钥管理 UI 无设备人工核对项：生成→复制公钥→选择密钥→打开主机；导入一对密钥（含口令）成功；
      公钥/私钥不匹配被拒；删除被引用密钥有警告。
- [ ] 真机人工验收（用户执行，步骤见 docs/install.md）：导入 config → 配置主机 → 打开 →
      默认在内置 WebView 显示远端 DSH UI；设置页两个开关与横栏收起/展开符合预期；通知稳定；杀进程后隧道退出。
