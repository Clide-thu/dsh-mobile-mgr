# 从零重建 APK（Windows，无 Gradle）

本文写给需要**自己构建 APK**的人（改代码、换签名、做二次分发）。只想要装 App 的用户请看
[README](../README.md) 和 [docs/install.md](install.md)。

构建产物：`dist/dsh-remote-hosts-<版本>.apk`（已签名、已对齐，可直接侧载安装）。
当前版本 **v0.2.5（versionCode 7）**，产物 `dist/dsh-remote-hosts-0.2.5.apk`：
**751845 字节（约 0.72 MB）**，SHA256 `b6c613b682a093d0aea02edf987fd5a2d2de23a1f1a16cbd53f55d511434a1d9`
（详见 [§8](#8-产物与分发)）。

---

## 1. 构建需要什么

| 需要 | 说明 |
|---|---|
| Windows 10 / 11 | 构建脚本按 **Windows PowerShell 5.1** 编写、测试 |
| Node.js | **≥ 20.11**（推荐 22/24 LTS）；`setup-toolchain.mjs` 用 Node 的 `fetch` 下载工具链 |
| 网络（仅第一次） | 下载 JDK 17、Android platform 35、build-tools 35、JSch、org.json，约 **300–400 MB** |
| `tar` 命令 | Windows 10 1803+ 自带 `bsdtar`（`C:\Windows\System32\tar.exe`）；老系统需自行提供 |

**不需要**：Android Studio、安装 JDK、安装 Android SDK、安装 Gradle、配置代理或 Maven 仓库。
工具链全部下载到项目内的 `.toolchain/`（已被 `.gitignore` 忽略），随用随删。

> 说明：如果 `node` 不在 PATH 里，把下文的 `node` 换成你机器上 Node 可执行文件的完整路径即可
> （例如 DSH 自带运行时目录里的 `node.exe`）。以下命令都假定你在**项目根目录**执行。

---

## 2. 第一次：准备工具链

```powershell
node tools\setup-toolchain.mjs
```

脚本会按顺序：

1. 下载 **Temurin JDK 17**（含 `javac`、`jar`、`keytool`）并解压到 `.toolchain\jdk\`；
2. 从 Google Android SDK 仓库清单解析并下载 **platform 35** 与 **build-tools 35.0.0**
   到 `.toolchain\android\`；
3. 从 Maven Central 下载 **JSch**（SSH 客户端）与 **org.json**（桌面 JVM 测试用）到 `.toolchain\libs\`；
4. 写出 `.toolchain\toolchain.json`（绝对路径清单，构建脚本读取它）。

重新下载可以用：

```powershell
node tools\setup-toolchain.mjs --force
```

成功后检查一下清单文件存在：

```powershell
Get-Content .toolchain\toolchain.json
```

> 下载缓存保存在 `.toolchain\downloads\`；如果中途失败，重跑会复用已下载的部分。
> 换机器或删了 `.toolchain\`，重新跑一次即可。

---

## 3. 构建 APK

签名密钥的密码**不在仓库里**：构建前先设置环境变量 `DSH_KEYSTORE_PASSWORD`
（密码只存在于你本机的环境变量 / 记忆中，不会写入源码或提交到 Git）：

```powershell
$env:DSH_KEYSTORE_PASSWORD = '<你的 keystore 密码>'
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1
```

* 第一次构建且 `keystore/release.jks` 不存在时，脚本会用这个密码**生成**一把新密钥
  （请随后备份 `keystore/release.jks` 和密码）。
* 只做**未签名**构建（`-SkipSign`）时不需要该环境变量。
* 如果忘记设置，签名构建会以明确的错误提示退出，而不是生成无法升级的 APK。

* 必须带 `-ExecutionPolicy Bypass`：Windows 默认执行策略会阻止本地 `.ps1` 脚本。
* 构建过程包含资源编译、Java 编译、dex、打包、对齐、签名和验签。工具链已缓存时通常 **20–60 秒**
  （v0.2.5 本次实测约 **22 秒**）；首次构建（含下载/解压工具链）可能几分钟。
* 成功时最后会打印 APK 路径、大小、sha256 和版本，例如（本次 v0.2.5 交付实测）：

  ```text
  ==> APK
    path      : C:\...\dsh-mobile-mgr\dist\dsh-remote-hosts-0.2.5.apk
    size      : 751845 bytes (0.72 MB)
    sha256    : b6c613b682a093d0aea02edf987fd5a2d2de23a1f1a16cbd53f55d511434a1d9
    version   : 0.2.5 (versionCode 7)
    ...
  BUILD OK
  ```

构建脚本每次会把中间产物写进独立的 `build\stage-<进程号>\` 目录（并清理旧的 `stage-*`），
默认不删除 `dist\` 里的旧产物（同名文件会被覆盖），加 `-Clean` 会先删除 `dist\*.apk`
（以及可能存在的 `*.apk.idsig`）。构建结束会自动做 `apksigner verify` 和 `zipalign -c 4` 自检。

### 可选参数

```powershell
# 自定义版本号（默认取 app/AndroidManifest.xml 里的 versionName/versionCode；
# 下面只是示例值，正式发布前记得同步改 manifest）
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1 -VersionName 0.2.6 -VersionCode 8

# 只构建未签名（但已对齐）的 APK，放在 dist\dsh-remote-hosts-<版本>-unsigned.apk
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1 -SkipSign

# 构建前清掉 dist\ 里的旧 APK / .idsig
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1 -Clean
```

> `-SkipSign` 的产物**不能直接覆盖升级**（没有签名，手机不会安装），只适合检查构建结果。
> 完整参数以 `tools\build.ps1` 文件头部的 `param(...)` 为准。

### 构建流水线（维护者参考）

`tools/build.ps1` 不使用 Gradle，直接调用便携工具链：

| 步骤 | 工具 | 说明 |
|---|---|---|
| 1 | `aapt2 compile` | 把 `app/res/**` 编译成 `.flat` 资源 |
| 2 | `aapt2 link` | 生成资源表 + `R.java` + 未签名的基础 APK；minSdk 26 / targetSdk 35 |
| 3 | `javac` | `-source 8 -target 8`，`-bootclasspath android.jar`，编译 `app/src/**` 与生成的 `R.java` |
| 4 | `jar` | 把 class 目录打成 `classes.jar`（d8 只接受 jar/class 文件，不接受目录） |
| 5 | `d8` | dex 化（`--min-api 26 --release`），并合并 JSch |
| 6 | 打包 | 把 `classes.dex` **追加**进基础 APK（不重新打包，避免把 `resources.arsc` 重新压缩） |
| 7 | `zipalign` | 4 字节对齐（`-p 4`） |
| 8 | `apksigner` | 用 `keystore/release.jks` 签名，并 `verify` 自检 |

几个 Windows 特有的坑（脚本里已处理，改脚本时注意）：

* **argfile 编码**：Windows PowerShell 5.1 的 `Out-File -Encoding utf8` 会带 BOM，`javac @argfile`
  会把 BOM 当成第一个参数的一部分而报错；脚本用 `[System.IO.File]::WriteAllLines(..., UTF8Encoding($false))`
  写出**无 BOM** 的文件。
* **追加 classes.dex**：Jar/Zip 工具重写 APK 时会**重新压缩** `resources.arsc`，而 targetSdk ≥ 30
  的 APK 要求它保持未压缩；脚本用 `System.IO.Compression.ZipArchive` 以 `NoCompression` 追加条目。
* **d8** 需要 `--lib android.jar`；JSch 作为输入一起 dex 化，避免运行时缺类。

---

## 4. 为什么没有 Gradle

本项目的构建环境一开始就**没有 JDK、没有 Android SDK、没有 Gradle，且网络出口受限**（只有 Node 的
`fetch` 可用）。为了让任何人拿到源码后能复现构建，采用「自带便携工具链 + 直接调用 SDK 命令行工具」的方案：

* 项目**零第三方 Android 依赖**（不用 AndroidX、不用 Compose、不用 Kotlin），所以不需要依赖解析；
* 只用平台 API（`android.jar`）和单个纯 Java 的 JSch jar；
* 工具链下载一次后可**离线构建**，可重复、可审查；
* 代价：没有 Gradle 的增量构建/变体管理，构建脚本自己处理资源、dex、签名——但本项目只有一个 APK、无 flavors，
  这个代价可以接受。

---

## 5. 签名密钥与覆盖升级

* 签名密钥文件：**`keystore/release.jks`**（`keystore/` 已被 `.gitignore` 忽略，**不会提交到仓库**；
  如果本地不存在，签名构建时 `build.ps1` 会用 `keytool` 和 `DSH_KEYSTORE_PASSWORD` 自动生成）。
* 别名 `dshremote`，密钥算法 RSA 2048，有效期 10000 天。
* 密钥密码从环境变量 **`DSH_KEYSTORE_PASSWORD`** 读取（见 §3），**不写入仓库**。
* **同一个 keystore 是覆盖升级的前提**：已经装了旧版 APK 的手机，只有新 APK 用同一把密钥签名，
  才能「覆盖安装并保留数据」；换密钥会导致「签名不一致」，必须先卸载（**会丢凭证**）。
* **备份 keystore**：如果要长期分发/给别人的手机升级，请把 `keystore/release.jks` 和密码妥善备份
  （密码建议放密码管理器）；弄丢或换掉之后，所有已安装用户都无法覆盖升级。
* 本仓库里的签名密钥/密码属于**侧载测试签名**，不是正式发布密钥；正式上架应用商店应**新生成**密钥并妥善保管。

---

## 6. 测试与校验

工具链就绪后，可以一条命令跑桌面 JVM 测试：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\test-jvm.ps1
```

覆盖内容（纯 Java、不依赖 Android 设备）：

* `CredentialUrl`：凭证 URL 解析（http/https、默认端口、缺 token、非法协议）与 token 掩码；
* `SshConfigParser`：`Host` 匹配（通配/取反/多 pattern/first-match-wins）、`Include` 警告、
  `ProxyJump` / `IdentityFile`、注释与引号；
* `HostsCodec`：主机 + **密钥库** JSON 编解码容错、旧版（无 `sshKeys`）数据兼容；
  **v0.2.5**：确认 `appendToken` 字段已删除、旧 JSON 里残留的该字段被**忽略**且主机其余字段正常解码；
* `LogSanitizer`：token/密码/私钥/口令脱敏；
* `SshKeyManager`（v0.2.0）：RSA 2048/3072/4096 生成、OpenSSH 公钥行格式、SHA256 指纹（标准 base64，
  与 `ssh-keygen -lf` 一致）、私钥可被 JSch 重新加载、导入公钥匹配/不匹配、口令正确/错误、id 唯一性；
* `HostsMigration`（v0.2.0）：v0.1.0 内联私钥 → 密钥库迁移（去重/幂等/不改其他字段）；
* `TokenProbeTest`（v0.2.1）：token 预校验的纯 JVM 测试（本地 `HttpServer` 覆盖 2xx/3xx→OK、
  401/403→REJECTED、IO→UNREACHABLE、404/500 不阻断、token 查询串编码、不跟随 303）；
* `TunnelEngineTest`（真实 SSH）：直连 + ProxyJump + **KeyResolver（按 keyId 从密钥库取密钥）** 路径。

其中包含依赖**真实 SSH 主机**的冒烟/集成测试（直连 + ProxyJump），它们**默认自动跳过**：
没有设置 `SSH_TEST_*` 环境变量时测试会输出 `SKIP` 并以退出码 0 结束（不会被误报为失败）。
如果你有可用的测试环境，按 `test/jvm/SshSmokeTest.java`、`test/jvm/TunnelEngineTest.java`
文件头注释设置 `SSH_TEST_DIRECT_HOST` / `SSH_TEST_JUMP_HOST` / `SSH_TEST_TARGET_HOST` 等变量
（密钥默认读 `~/.ssh/id_rsa`，可用 `SSH_TEST_KEY` 指定）即可让它们真正连接。

只想跑**离线单元测试**（不碰网络）时，显式加 `-SkipSsh`：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\test-jvm.ps1 -SkipSsh
```

做**发布验收**（要求真实 SSH 测试必须真的跑过、不能以"未配置"跳过）时，加 `-RequireSsh`：

```powershell
# 先按测试文件头注释设置好 SSH_TEST_* 环境变量，再执行
powershell -NoProfile -ExecutionPolicy Bypass -File tools\test-jvm.ps1 -RequireSsh
```

测试用的是**只读**操作（建隧道、读 SSH banner），不会修改远端文件。

构建完成后校验 APK：

```powershell
# 默认检查 dist/ 里最新的 APK，并期望 versionName=0.2.5、versionCode=7
powershell -NoProfile -ExecutionPolicy Bypass -File tools\check-apk.ps1

# 或显式指定一个 APK / 覆盖期望的版本号
powershell -NoProfile -ExecutionPolicy Bypass -File tools\check-apk.ps1 -Apk dist\dsh-remote-hosts-0.2.5.apk
powershell -NoProfile -ExecutionPolicy Bypass -File tools\check-apk.ps1 -ExpectVersionName 0.2.5 -ExpectVersionCode 7
```

它会做静态检查并输出报告（任一项失败退出码非 0），包括：

* `apksigner verify`：签名有效；
* `zipalign -c -v 4`：对齐正确；
* `aapt2 dump badging`：包名、versionName/versionCode（v0.2.5 = `0.2.5` / `7`）、minSdk 26、targetSdk 35、权限、Service 声明；
* manifest 检查：**6 个 Activity**（`MainActivity` + `EditHostActivity` + `ImportConfigActivity` + `SshKeysActivity`
  + **v0.2.5 新增的 `SettingsActivity`** + `WebViewActivity`，后五者均 `exported=false`）、`TunnelService`
  `exported=false` + `dataSync`、`allowBackup=false`、无 debuggable；
* zip 内容检查：`classes.dex` 存在、`resources.arsc` 未压缩、无 `.toolchain` 等构建残留泄漏；
* v0.2.5 功能内建检查：确认设置页两个开关的 string 资源、编辑页设置提示、`settings` 相关文案与
  WebView 标题 / 错误面板 / 横栏收起等资源已编入 APK（具体 key 以 `tools\check-apk.ps1` 为准）；
* 敏感信息扫描：确认 APK 内**没有**测试用 token / 密码 / **私钥明文** / 真实主机信息。

> 没有 Android 真机/模拟器时，以上「编译 + JVM 测试 + APK 静态校验」就是可自动完成的全部验证；
> 真机安装与人工验收步骤见 [docs/install.md §9](install.md#9-真机首次验收清单可选但建议做一遍)。
> 本次 v0.2.5 交付实测：`test-jvm.ps1 -SkipSsh` **7 组单测共 415 checks** 全 PASS
> （CredentialUrl 59、SshConfigParser 62、HostsCodec 85、LogSanitizer 36、SshKeyManager 86、
> HostsMigration 60、TokenProbe 27）；
> `test-jvm.ps1 -RequireSsh`（真实 SSH：SshSmokeTest + TunnelEngineTest）全 PASS 且**无 SKIP**；
> `check-apk.ps1` **76/76 PASS**（含 6 Activity、设置页/横栏收起 string 资源检查）。

---

## 7. 改版本号

版本号来源是 `app/AndroidManifest.xml` 的前几行（v0.2.5 已升到 `versionCode=7`）：

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="dev.dsh.remotehosts"
    android:versionCode="7"
    android:versionName="0.2.5">
```

* **`versionName`**：显示给用户的版本（**「更多」（⋮）→ 关于** 里显示），也是产物文件名
  `dist/dsh-remote-hosts-<versionName>.apk` 的一部分；
* **`versionCode`**：系统用来判断升级的整数，**每次发布必须递增**（否则覆盖安装会被拒绝；
  0.1.0→0.2.0→0.2.1→0.2.2→0.2.3→0.2.4→0.2.5 必须从 1→2→3→4→5→6→7 才能覆盖升级）。

改完直接重新构建即可；也可以用 `-VersionName` / `-VersionCode` 参数临时覆盖（不会改 manifest 文件）。

---

## 8. 产物与分发

**本次交付产物**（v0.2.5，可直接分发）：

| 项 | 值 |
|---|---|
| 文件 | `dist/dsh-remote-hosts-0.2.5.apk` |
| 大小 | **751845 字节（约 0.72 MB）** |
| versionName / versionCode | `0.2.5` / `7` |
| 包名 | `dev.dsh.remotehosts` |
| minSdk / targetSdk | 26（Android 8.0） / 35 |
| 签名 | v2 + v3（v1/v4 已关闭；因此 `dist/` 里没有 `.idsig` 文件） |
| SHA256 | `b6c613b682a093d0aea02edf987fd5a2d2de23a1f1a16cbd53f55d511434a1d9` |
| 测试结果 | `test-jvm -SkipSsh`：**7 组单测 415 checks** PASS；`test-jvm -RequireSsh`：真实 SSH 全 PASS、无 SKIP；`check-apk`：**76/76 PASS**（含 6 Activity、设置页/横栏收起资源检查） |

* **构建可复现（v0.2.5 已实测）**：构建脚本固定了打包内容与 zip 时间戳，并对齐签名方案（v2+v3），同一份源码 +
  同一个 keystore 连续两次独立构建（含 `-Clean`）得到的 APK **sha256 完全一致**
  （`b6c613b6…34a1d9`，751845 字节）。
* **分发前记录哈希**，方便接收者核对：

  ```powershell
  Get-FileHash .\dist\dsh-remote-hosts-0.2.5.apk -Algorithm SHA256
  ```

* `-SkipSign` 会生成 `dist/dsh-remote-hosts-<versionName>-unsigned.apk`：**未签名、不能安装**，
  只用于检查构建产物（调试用途）。
* 签名要点：APK 由项目内 `keystore/release.jks`（alias `dshremote`）签名；**同一 keystore 是覆盖升级的前提**，
  因此 v0.1.0 → … → v0.2.5 可以用同一签名直接覆盖安装（v0.1.0→0.2.0 旧数据自动迁移，
  0.2.1 起的各小版本均无数据迁移变化；0.2.4→0.2.5 已实测覆盖安装后设备数据保留，见
  [docs/install.md §7.7](install.md#77-从-v024-升级到-v025重要行为变化)）。
* 安装到手机：见 [docs/install.md](install.md)。快速方式是 USB 调试 + `adb install -r <APK 路径>`。
* 本 App **没有**自动更新/应用商店，分发新版本 = 把新 APK 发给用户手动覆盖安装。

---

## 9. 常见构建问题

| 现象 | 原因 | 解决 |
|---|---|---|
| `toolchain manifest missing` / `toolchain component ... missing` | `.toolchain/toolchain.json` 或其中某个组件不存在 | 先跑第 2 节的命令；若组件缺失可 `node tools\setup-toolchain.mjs --force` 重新下载 |
| `无法加载文件 …build.ps1，因为在此系统上禁止运行脚本` | PowerShell 执行策略 | 用 `powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1` |
| `node : 无法将"node"项识别为 cmdlet...` | Node 不在 PATH | 安装 Node.js LTS，或用 Node 可执行文件的完整路径运行 |
| 下载 JDK/SDK 失败 | 网络问题 | 重跑（会复用缓存）；或 `--force` 后重试；公司网络需要能访问 `api.adoptium.net`、`dl.google.com`、`repo.maven.apache.org` |
| `aapt2 compile` / `link` 报资源错误 | `app/res/**` 有语法错误或引用了不存在的 string | 看错误里给出的文件与行号，修正资源；不要手改 `build/gen`（每次生成） |
| `javac` 报错 | `app/src/**` Java 语法/API 问题 | 按错误里的 `文件:行` 修复；注意纯 Java 8 语法、只能用平台 API（无 AndroidX） |
| `d8` 报错 `classes.dex` 未生成 | Java 编译产物/classpath 问题 | 先确认 `javac` 成功；检查 `.toolchain/toolchain.json` 里 `d8Jar` 路径有效 |
| `keytool` 报 `Keystore was tampered with, or password was incorrect` | `keystore/release.jks` 已存在但密码不是脚本里的 | 找回原密码/keystore；**不要直接删除**，否则已装用户无法覆盖升级（只能卸载重装，数据丢失） |
| `apksigner verify` 失败 | 签名步骤异常 | 检查 `keystore` 权限与 `apksignerJar` 路径；删掉 `build\stage-*` 后重跑 |
| 构建很慢/卡住 | 首次下载大文件；杀毒软件扫描 | 等待；把项目目录加入杀毒软件白名单 |
| `tar` 不存在（解压失败） | Windows 版本过老 | 升级到 Windows 10 1803+，或把 `tar.exe` 放到 PATH |

构建通过但 App 行为不对？先跑 `tools\check-apk.ps1` 确认 APK 结构，再按
[docs/troubleshooting.md](troubleshooting.md) 排查真机问题。

---

## 10. 目录速览

```text
dsh-mobile-mgr/
├── app/
│   ├── AndroidManifest.xml      # 包名/版本/权限/组件声明（v0.2.5：6 个 Activity，含 SettingsActivity/WebViewActivity）
│   ├── res/                     # 布局、文案、颜色、图标、网络安全配置
│   └── src/dev/dsh/remotehosts/
│       ├── core/                # SSH 解析、加密存储、密钥管理/迁移、设置存储、隧道引擎、前台服务
│       └── ui/                  # 主机列表 / 编辑 / 导入 / SSH 密钥管理 / 设置 / 内置 WebView
├── docs/                        # 架构、行为契约、安装、安全、排查、构建（本文）
├── keystore/release.jks         # 本地测试签名密钥（覆盖升级的关键，务必保管）
├── test/jvm/                    # 桌面 JVM 测试（不依赖 Android）
├── tools/                       # setup-toolchain / build / test-jvm / check-apk
├── dist/                        # 构建产物（APK）
└── .toolchain/                  # 便携工具链与下载缓存（gitignore，不随源码分发）
```
