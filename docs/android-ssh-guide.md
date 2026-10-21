# 在 Android 上配置 SSH，并让 App 访问 `.ssh` 目录

> 适用版本：Android 8–17；Termux（F-Droid / GitHub 版）；本仓库 App `dev.dsh.remotehosts`（minSdk 26，targetSdk 35）。
> 本文回答两个问题：**① 手机上的 SSH 怎么配**；**② 第三方 App 能不能、以及怎样才能拿到 `~/.ssh` 里的 config 和私钥**。
>
> **与 App 内流程的对应关系（v0.2.0）**：本 App **不要求**你安装 Termux，也不读取任何 `.ssh` 目录；它的实际使用路径是
> 「导入 SSH config 文本/文件 → 在 **SSH 密钥管理**里生成/导入密钥（主机选密钥）→ 粘贴 `dsh web` 凭证 URL → 点打开」
> （见 [README 快速开始](../README.md#3-5-分钟快速开始) 和 [docs/install.md §4](install.md#4-首次使用8-步)）。
> 本文是**进阶背景知识**：解释 Android 为什么不能直接读 `~/.ssh`、以及你已有 Termux SSH 配置时如何安全迁移。

## 0. 结论先行

1. **Android 系统本身不带 ssh/sshd**。要“在手机上配置 SSH”，只有三条路：
   * 装 **Termux**（推荐），用里面的 OpenSSH 客户端/服务端；
   * 用自带 SSH 引擎的 App（Termius、ConnectBot、SimpleSSHD 等，或本仓库 App 内嵌的 JSch）；注意主流 SSH 客户端都是“导入/粘贴/内建生成密钥”，**没有谁会去读 Termux 的 `~/.ssh`**；
   * root 后把 OpenSSH 塞进系统（不推荐）。
2. **非 root、非系统 App、且不共享 `sharedUserId` 的前提下，没有任何“普通权限”能让 App 静默读取另一个 App 的私有目录**（Linux UID + SELinux 双重隔离）。Termux 的 `~/.ssh` 实际路径是
   `/data/data/com.termux/files/home/.ssh`，属于 `com.termux`，别的 App 直接 `open()` 必然 `EACCES`。
3. **但“允许访问”是能做到的，前提是用户显式授权**：Termux 官方导出了一个 SAF DocumentsProvider（`com.termux.documents`，根目录 = `$HOME`），用户可以在系统文件选择器里把 `~/.ssh`（或其软链接）授权给某个 App；另外 `termux-open --send` / `RUN_COMMAND` 也是官方通道。区别只是“不能偷偷读”，而不是“完全无路”。
4. **`/sdcard/.ssh` 不是可靠方案**：Android 10+ 分区存储、DocumentsUI 默认隐藏点号开头的文件、SAF 文档提供器不保证暴露隐藏目录；把私钥放共享存储本身也是安全降级。
5. 工程上首选的模型是 **“App 内生成，只交换公钥”**（v0.2.0 已内置）：
   * **首选**：在 App 的「SSH 密钥管理 → 生成新密钥」生成手机专用 RSA 密钥，私钥只存在 App 的
     加密存储里，**只把公钥**复制到远端 `~/.ssh/authorized_keys`；
   * 其次：把已有私钥通过 SAF/粘贴**导入** App 的密钥库（加密存进 App 私有目录，AES-256-GCM + Android Keystore）；
   * 若一定要“复用 Termux 里的 SSH”，让 App **不碰私钥**，改为委托 Termux 执行 ssh（`RUN_COMMAND` Intent 或把隧道建在 Termux 里）；或让用户在 SAF 里显式授权 `$HOME` 下的 `~/.ssh` 软链接（见 §5.2.1）。
6. 因此：**本仓库 App 不应申请 `MANAGE_EXTERNAL_STORAGE`，也不应尝试静默扫描 `~/.ssh`**；`HostConfig.identityFiles` 只做展示，真正可用的是导入进加密密钥库、主机通过 `keyId` 引用的密钥（对应 `docs/app-spec.md` §1/§4/§8、`docs/architecture.md` §3.1/§4）。

---

## 1. 三种“在安卓上配置 SSH”的场景

| 场景 | 需要的东西 | 典型用途 |
|---|---|---|
| A. 手机当 **SSH 客户端** | Termux + openssh，或 SSH App | 从手机登录 PC/服务器；本仓库 App 则是内嵌 JSch 建隧道 |
| B. 手机当 **SSH 服务端** | Termux + openssh `sshd`（默认 8022） | 从电脑 `ssh -p 8022` 进手机；传文件（sftp/rsync） |
| C. App **内嵌 SSH 引擎** | 纯 Java 的 JSch（本仓库） | App 自己建 `-L` 隧道，不依赖系统 ssh |

本仓库属于 C，但用户很可能同时在手机上用 Termux 做 A/B；两者可以共存，只是**密钥与 config 不共享**。

---

## 2. 场景 A：用 Termux 配 SSH 客户端

### 2.1 安装 Termux

* **优先 F-Droid**：<https://f-droid.org/packages/com.termux/>（Termux 官方推荐渠道）。
* 想要最新版可用 GitHub Releases（例如 `v0.118.x`，以 Releases 页为准；Android ≥7 选 `apt-android-7` 变体）；GitHub 构建用公开测试密钥签名，只从官方仓库下载。
* Google Play 版从 2024-06 起以独立代码库恢复上架（仅 Android 11+），官方定性为“实验性、有稳定性问题”，且 2026 年在 Android 17 上出现过局域网访问被“本地网络保护”默认封禁的问题（[termux-issues #1358](https://github.com/termux-play-store/termux-issues/issues/1358)）——能装 F-Droid 就装 F-Droid。
* **不同签名渠道的 Termux 不能互相覆盖安装**，换渠道前先备份 `~/` 并卸载全部 Termux 插件。

### 2.2 装 OpenSSH、生成密钥

```bash
pkg update && pkg upgrade
pkg install openssh

# 默认算法：ed25519，带口令更安全（回车两次 = 不设口令）
ssh-keygen -t ed25519 -a 100 -C "pixel-termux"

# 目录与权限（Termux 通常已正确，手动改错后 ssh 会直接拒绝使用密钥）
chmod 700 ~/.ssh
chmod 600 ~/.ssh/id_ed25519 ~/.ssh/config ~/.ssh/authorized_keys 2>/dev/null
chmod 644 ~/.ssh/id_ed25519.pub 2>/dev/null
```

把公钥装到目标机（目标机需先允许密码登录）：

```bash
ssh-copy-id -i ~/.ssh/id_ed25519.pub user@192.168.1.10
# 或手动把 id_ed25519.pub 内容追加到目标机 ~/.ssh/authorized_keys
```

> Termux 的 `openssh` 自带 `ssh-copy-id`，不需要另装。

> 如果目标机是 **本仓库 App 要连的那台**：**推荐直接在 App 内生成一把新的手机专用密钥**
> （「更多」（⋮）→ SSH 密钥管理… → 生成新密钥），把它生成的公钥装到远端 `authorized_keys`，
> 不必把 Termux 里的私钥交给 App（见 §5.1）。只有服务器只授权了 Termux 现有那把公钥时，才需要把
> 私钥导入 App 的密钥库（App 会加密保存，见 §5.2）；**不要把私钥粘进聊天软件/邮件**。

### 2.3 `~/.ssh/config`（Termux 里同样适用）

```sshconfig
Host pc
  HostName 192.168.1.10
  User me
  Port 22
  IdentityFile ~/.ssh/id_ed25519
  ServerAliveInterval 30
  ServerAliveCountMax 3

Host jump
  HostName 203.0.113.7
  User ops
  Port 20000

Host gpu
  HostName 10.0.0.5
  User me
  # 先连 jump，再由 jump 连 gpu
  ProxyJump jump
```

使用：

```bash
ssh pc
ssh -J jump me@10.0.0.5
ssh -v pc                 # 排查时看协商/认证细节
```

`~/.ssh/config` 的语义（首次匹配生效、`Host *` 全局默认值、`Include` 等）与桌面 OpenSSH 一致；
本仓库的 `SshConfigParser` 实现的是这套语义的**常用子集**，可直接复用导入逻辑：

* 已实现：`Host` 多 pattern / `*` `?` 通配 / `!` 取反、首值生效（文件顺序敏感）、
  `Host` 块外的全局默认值、`HostName` / `User` / `Port` / `ProxyJump` / `IdentityFile`（可多次）、
  `keyword=value` 写法与 `#` 注释、`Include`（单文本解析时只给警告；`Map` 重载可在提供文件内容时展开）、
  `Match` 块不建模（其后的指令不再归属前一个 `Host`，避免误解析）。
* 未建模：`Match` 条件、`CanonicalizeHostname`、`HostKeyAlias` 等高级指令；只输出**字面量 alias**
  （跳过 `*`/`?`/`!` 开头的 pattern），保持文件顺序去重。

> 也就是说：普通电脑上的 `~/.ssh/config` 导入后与 `ssh <alias>` 行为基本一致；用了 `Match` 或复杂
> `Include` 嵌套的配置需要人工检查预览里的主机列表。

### 2.3.1 Termux 里这些文件到底在哪

`$HOME` = `/data/data/com.termux/files/home`，`$PREFIX` = `/data/data/com.termux/files/usr`（Termux 私有目录，其他 App 不可读）：

| 文件 | 实际路径 |
|---|---|
| 用户主目录 | `/data/data/com.termux/files/home` |
| SSH 配置 | `$HOME/.ssh/config` |
| 密钥对 | `$HOME/.ssh/id_ed25519`、`$HOME/.ssh/id_ed25519.pub` |
| 已知主机 | `$HOME/.ssh/known_hosts` |
| 服务端授权公钥 | `$HOME/.ssh/authorized_keys` |
| sshd 配置 | `$PREFIX/etc/ssh/sshd_config` |
| sshd 主机密钥 | `$PREFIX/etc/ssh/ssh_host_*_key` |
| Termux 属性 | `$HOME/.termux/termux.properties` |
| 开机脚本 | `$HOME/.termux/boot/*`（需 Termux:Boot） |

> 记住这一点：**App 想直接读的就是 `/data/data/com.termux/files/home/.ssh`，非 root 下静默读取不可能**；用户显式授权见 §5.2.1（见 §4）。

### 2.4 known_hosts 与常见错误

| 现象 | 原因 / 处理 |
|---|---|
| `WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED` | 服务器换机或中间人。确认无误后 `ssh-keygen -R host` 再连（带端口要写 `ssh-keygen -R '[host]:8022'`）；App 侧对应 `HOST_KEY_CHANGED`，应弹指纹确认，**不要静默信任** |
| `Permission denied (publickey)` | 本机 `~/.ssh` 权限应为 700、私钥 600；`ssh -v` 看是否用了对的 `IdentityFile`；服务器 `authorized_keys` 权限 600 |
| `Bad permissions` / `UNPROTECTED PRIVATE KEY FILE` | 本机私钥权限不是 600，`chmod 600` 后重试 |
| `Connection refused` | sshd 没起、端口错（手机做服务端是 8022）、防火墙/路由器隔离 |
| `Could not resolve hostname` | `HostName` 写错；Termux 的 DNS 与系统一致，可 `ping`/`getent hosts` 验证 |

---

## 3. 场景 B：手机当 SSH 服务端（Termux `sshd`）

```bash
pkg install openssh
passwd                 # 设置 Termux 用户密码（whoami 得到用户名）
sshd                   # 默认监听 8022
whoami                 # 例如 u0_a123，登录名就是它
ip -4 addr             # 或 ifconfig；查手机局域网 IP（通常在 wlan0）
pkill sshd             # 停止
```

从电脑连：

```bash
ssh -p 8022 u0_a123@192.168.1.23
sftp -P 8022 u0_a123@192.168.1.23
```

公钥登录（推荐，之后可关密码）：

```bash
# 手机端 ~/.ssh/authorized_keys 追加电脑公钥内容
chmod 600 ~/.ssh/authorized_keys
# 手机端编辑 $PREFIX/etc/ssh/sshd_config：
#   PasswordAuthentication no
pkill sshd; sshd
```

要点与坑：

* **端口永远是 8022**（Termux 非 root 不能绑 22；这个默认端口是编译进 openssh 的），且只在局域网/组网（Tailscale、WireGuard）内可达更安全。
* **不会开机自启**：需要 [Termux:Boot](https://wiki.termux.com/wiki/Termux:Boot)（F-Droid 单独装，授权后把启动脚本放 `~/.termux/boot/`，脚本首行用 `#!/data/data/com.termux/files/usr/bin/sh`）：

  ```sh
  # ~/.termux/boot/start-sshd
  #!/data/data/com.termux/files/usr/bin/sh
  termux-wake-lock
  sshd
  ```

  或者用 `termux-services` 的 `sv-enable sshd`（需 `pkg install termux-services`，重启 Termux 后生效；默认带 `down` 文件故需 `sv-enable`）。
* **锁屏/省电会杀进程**：Android 12+ 的 Phantom Process Killer 会在后台进程过多/CPU 过高时杀掉 Termux 子进程（表现为 `[Process completed (signal 9)]`）。缓解：
  * 系统设置里把 Termux 设为“不受限制/允许后台活动”；
  * 需要长连接时在 Termux 里 `termux-wake-lock`（取消用 `termux-wake-unlock`，都在 `termux-tools` 里）；
  * Android 12L/13+ 可用 adb 关闭：`adb shell settings put global settings_enable_monitor_phantom_procs false`（Android 12 本身没有这个开关；Android 14+ 也可在开发者选项里开 “Disable child process restrictions”，但关开发者选项会失效）；
  * Android 15 上还有个坑：Termux 前台服务若没有通知（没有 session/wakelock/任务），服务会被停，**sshd 进程还在但连接挂起**。所以 boot 脚本里加 `termux-wake-lock`，或让 `sshd -D` 前台运行。
* **不要暴露到公网**：无公网 IP 时用 Tailscale/ZeroTier/自建 WireGuard，比端口映射安全得多。
* `sshd` 日志在 Android logcat：`logcat -s 'sshd:*'`。

---

## 4. 为什么 App 读不到 `~/.ssh`

先澄清“`.ssh` 目录”可能指三样东西：

| 你指的 `.ssh` | 实际路径 | 第三方 App 能读吗 |
|---|---|---|
| Termux 用户的 `~/.ssh` | `/data/data/com.termux/files/home/.ssh` | ❌ 直接读不可能；✅ 用户可在 SAF 里选中它的非点号软链接授权（§5.2.1） |
| 共享存储里的 `.ssh` | `/sdcard/.ssh`（`/storage/emulated/0/.ssh`） | ⚠️ 理论上 SAF 可选，实际被隐藏规则/提供器限制，且不安全 |
| App 自己的 `.ssh` | `context.getFilesDir()/.ssh` 或 App 私有目录 | ✅ 完全可控，推荐用它做等价实现 |

下表解释为什么第一种不可能：

| 事实 | 说明 | 依据 |
|---|---|---|
| 每 App 独立 UID + 私有数据目录 | `/data/data/<pkg>` 属主是该 UID，其他 App 连目录都进不去；`/data/data/com.termux/files/home/.ssh` 只属于 Termux | Android 应用沙箱（[App data and files](https://developer.android.com/training/data-storage/app-specific)、[AOSP Application Sandbox](https://source.android.com/docs/security/app-sandbox)） |
| 分区存储 | Android 10+ 默认分区存储；Android 11+ 连 `/sdcard/Android/data` 都被系统挡掉 | [Scoped storage](https://developer.android.com/about/versions/11/privacy/storage) |
| SAF 是“用户授权”而不是“程序可读” | Termux 确实导出了 DocumentsProvider（`com.termux.documents`，根 = `$HOME`，`$PREFIX` 不暴露），但只有用户在系统选择器里亲手选中的文件/目录才会把 URI 权限授予 App；DocumentsUI 默认隐藏 `.` 开头项，需要用非点号软链接绕过 | [TermuxDocumentsProvider.java](https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/filepicker/TermuxDocumentsProvider.java) |
| `MANAGE_EXTERNAL_STORAGE` 也读不到 | “所有文件访问”只覆盖共享存储，**不覆盖其他 App 的私有目录**；且上架 Play 需特殊声明 | [All files access](https://developer.android.com/training/data-storage/manage-all-files) |
| root 例外 | `su` 后可以 `cat /data/data/com.termux/files/home/.ssh/id_ed25519`；`adb pull` 在非 debuggable 的 Termux 上不可用（`run-as` 仅 debuggable App） | — |

**所以非 root 下有两条路：① 用户显式授权（SAF / 分享 / `RUN_COMMAND`）后由 App 读取或使用；② root。没有静默读取的办法。**

---

## 5. 让 App 拿到 SSH 材料：可行方案对比

> v0.2.0 起，本仓库 App **已内置最安全的两条路径**：A（App 内生成）与 B（SAF 导入**到密钥库**）。
> 日常使用推荐 A：私钥从不离开 App，只把公钥装到服务器；不要为了“省事”去和 Termux/电脑共用同一把私钥。

| 方案 | 需要 root | 私钥是否离开原 App/存储 | 体验 | 推荐度 |
|---|---|---|---|---|
| A. App 内生成密钥，只导出公钥 | 否 | 私钥不离开 App | 最好（**v0.2.0 内置**） | ★★★★★ |
| B. SAF 文件选择器导入私钥/`config` 文本 | 否 | 用户手选文件，加密复制进 App | 好（**v0.2.0 内置**，导入到密钥库） | ★★★★★ |
| B2. SAF 直接选 Termux home 里的 `~/.ssh` 软链接 | 否 | 用户显式授权目录/文件 | 好（需绕过点号隐藏） | ★★★★ |
| C. 粘贴私钥/`config` 文本 | 否 | 同上（经剪贴板，注意剪贴板历史） | 好（**v0.2.0 内置**，导入到密钥库） | ★★★★ |
| D. Termux 导出到共享存储 → SAF 导入 → 删临时文件 | 否 | 临时落共享存储 | 一般 | ★★★ |
| E. Termux `termux-share`/`termux-open` 分享给 App | 否 | 经 ContentProvider URI 直接传 | 好（需 App 支持接收，**当前未实现**） | ★★★★ |
| F. Termux `RUN_COMMAND` 委托：App 不读私钥，让 Termux 跑 ssh | 否 | 私钥始终在 Termux | 架构不同 | ★★★（若要复用 Termux 配置） |
| G. 共享存储 `/sdcard/.ssh` 直接读 | 否 | 私钥在共享存储 | 不可靠/不安全 | ☆（不推荐） |
| H. root / `adb pull` | 是 | 是 | 差 | ☆（仅调试） |

### 5.1 A. App 内生成密钥（最安全；v0.2.0 已内置）

**这也是本仓库 App 当前推荐的首选路径**：在 App 的 **「更多」（⋮）→ SSH 密钥管理… → 生成新密钥**
里直接生成 **RSA 2048/3072/4096**（默认 3072）密钥对：

* 私钥**从生成起**就只存在于 App 的加密存储（AES-256-GCM + Android Keystore），不落文件、不进
  剪贴板、不经网络；
* 生成完成后界面展示 **OpenSSH 公钥行**（`ssh-rsa AAAA... label`），点「复制公钥」；
* 把公钥装到远端 `~/.ssh/authorized_keys`（一整行），主机编辑页选择该密钥即可。

如果你更想在电脑/Termux 上生成再导入，也可以（这种方式**不再推荐给日常使用**，因为私钥要多走一段
文件/剪贴板路径；仅适合“服务器已授权了某把现成公钥”的情况）：

```bash
# 电脑 / Termux / 任意 Linux：生成一对“手机专用”密钥
ssh-keygen -t rsa -b 3072 -f phone_key -C "dsh-mobile-app"   # 兼容性最稳
# 或 ssh-keygen -t ed25519 -f phone_key                     # 新、短，但见 §6 兼容性
cat phone_key.pub        # 把公钥装到远端 authorized_keys；私钥在 App 里导入
```

> v0.2.0 的密钥管理**已支持**生成与复制公钥；**尚不支持**导出公钥文件或二维码（roadmap）。
> 私钥留在 App 的加密存储里，不要导出回文件——除非你在做一次性迁移。

### 5.2 B. SAF 导入（v0.2.0 内置，导入到密钥库）

```java
// v0.2.0：SshKeysActivity「导入密钥对」里的“从文件导入私钥…”
Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
i.addCategory(Intent.CATEGORY_OPENABLE);
i.setType("*/*");                       // 私钥常无扩展名，不要限定 text/*
i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
        "text/*", "application/octet-stream", "application/x-pem-file"});
startActivityForResult(i, REQ_PICK_KEY);
// onActivityResult: 读全文（FileTextReader，上限 1 MiB）→ SshKeyManager.importKeyPair(...)
// → HostStore.upsertSshKey(record)（AES-256-GCM 加密落盘）
```

* **v0.2.0 起私钥不再从主机编辑页导入**：统一进入「SSH 密钥管理」的密钥库；主机编辑页只做“选择密钥”
  （下拉框），主机保存的是 `keyId` 引用。
* 私钥可能是 `text/plain` 也可能是 `application/octet-stream`，所以 `*/*` 更稳；导入后校验首行
  （`-----BEGIN OPENSSH PRIVATE KEY-----` / `-----BEGIN RSA PRIVATE KEY-----` / `-----BEGIN PRIVATE KEY-----`）。
* 读取后立即写入密钥库（加密），不要缓存到 `SharedPreferences`、日志或 `Intent` extras；导入完成后删除临时文件。
* 不需要 `READ_EXTERNAL_STORAGE`/`MANAGE_EXTERNAL_STORAGE`；SAF 授权是逐文件的。
* `~/.ssh/config` 同样可以用 SAF 选入（**导入 SSH 配置 → 选择文件…**）→ 走 `SshConfigParser.parse()`
  （注意 `Include` 无法展开，只给 warning）。

#### 5.2.1 直接让用户在 SAF 里选中 Termux 的 `~/.ssh`

Termux 在 Manifest 里注册了 `TermuxDocumentsProvider`（authority `com.termux.documents`，根目录就是 `$HOME`，`$PREFIX` 不暴露），所以第三方 App 的 `ACTION_OPEN_DOCUMENT` / `ACTION_OPEN_DOCUMENT_TREE` 的来源侧栏里通常会出现 **Termux**。但系统 DocumentsUI 默认隐藏 `.` 开头的文件/目录，`~/.ssh` 点不进去，常用绕法是做一个非点号软链接（Termux 文档对 `~/.termux` 等隐藏目录也建议类似做法）：

```bash
# 在 Termux 里执行
ln -s ~/.ssh ~/dsh_ssh
```

然后 App 侧：

```java
Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);   // 选目录
i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
         | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
startActivityForResult(i, REQ_PICK_TREE);
// onActivityResult: getContentResolver().takePersistableUriPermission(
//     uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
// 之后用 DocumentFile / DocumentsContract 遍历该 URI 读 config、id_ed25519
```

* 优点：用户是“亲手授权”的，私钥不需要先落到 `/sdcard`；App 只拿到被选目录子树的 URI，不是整个 Termux home。
* **务必只选 `dsh_ssh` 这个子目录，不要选 Termux 根目录**——选根等于把整个 `$HOME`（shell 历史、其他密钥等）都授权出去。
* 缺点/坑：不同 ROM 的 DocumentsUI 对点文件/软链接的展示不一致；授权粒度粗（整个目录），App 应只读 `config` 与指定私钥，不去枚举其他文件；用户换 Termux 安装/清数据后 URI 失效，需要重新授权。
* 安全：只对信任的 App 用；导入完成后 App 应复制进自身加密库并尽快释放 URI；用户可通过 App 内“取消授权”或清数据撤销。

### 5.3 C. 粘贴导入

Termux 里 `cat ~/.ssh/config`、`cat id_ed25519.pub` 后长按复制，App 粘贴框接收。
私钥全文粘贴时注意：不要落剪贴板历史/输入法云同步；App 里应屏蔽 `textPassword` 的自动填充与日志。

### 5.4 D. Termux 导出到共享存储（过渡方案）

```bash
termux-setup-storage          # 授权 Termux 访问 /sdcard（只需一次）
mkdir -p /sdcard/Download/dsh-ssh
cp ~/.ssh/config /sdcard/Download/dsh-ssh/config
cp ~/.ssh/id_ed25519.pub /sdcard/Download/dsh-ssh/id_ed25519.pub
# 私钥仅在确实要迁移时导出；先在本机加上强口令，再复制（/sdcard 不支持 chmod，顺序很重要）
ssh-keygen -p -f ~/.ssh/id_ed25519        # 设强口令
cp ~/.ssh/id_ed25519 /sdcard/Download/dsh-ssh/id_ed25519
```

然后在 App 里用 SAF 从“下载”选文件导入；**导入成功后立刻删掉 `/sdcard/Download/dsh-ssh`**：

```bash
rm -rf /sdcard/Download/dsh-ssh
```

> 不要把私钥放进云同步目录（Google Drive/网盘/相册备份）或 `git`；共享存储上的文件其他 App 也可能读到（旧版 Android/文件管理器/恶意 App）。

### 5.5 E. 从 Termux 直接“分享”给 App

```bash
# termux-open 在 termux-tools 里，开箱即用；--send 走 ACTION_SEND
termux-open --send --content-type text/plain ~/.ssh/config
termux-open --send ~/.ssh/id_ed25519.pub

# termux-share 属于 termux-api 包，还需要装 Termux:API 应用
pkg install termux-api
termux-share -a send ~/.ssh/id_ed25519.pub
```

`termux-open`/`termux-share` 会把文件通过 Termux 的 FileProvider（`content://com.termux.files/...`）以临时读权限交给系统分享面板；接收 App 从 `Intent.EXTRA_STREAM` 读取即可。
**前置条件**：Termux ≥ 0.118 必须先在 `~/.termux/termux.properties` 里设 `allow-external-apps=true`（改完 `termux-reload-settings` 或重启 Termux），否则 `termux-open --send` 会被 Termux 拒绝（[Sharing Data](https://wiki.termux.dev/wiki/Sharing_Data)）。
若本仓库 App 想支持这条路，需要声明接收 `ACTION_SEND`（`text/plain` + `application/octet-stream`），注意校验来源与大小，读完即加密入库。
（分享面板里不要选聊天软件/邮件，避免私钥外泄。）

### 5.6 F. 让 Termux 代跑 ssh（App 不碰私钥）

如果目标是“复用 Termux 里已经配好的 `~/.ssh/config` 和 agent”，正确的做法是**把 ssh 进程留在 Termux**，App 只发指令：

1. Termux 设置里打开 `allow-external-apps=true`（`~/.termux/termux.properties`）；
2. App 声明权限 `com.termux.permission.RUN_COMMAND`（用户还要在系统设置里手动授予“在 Termux 环境中运行命令”），并且 targetSdk ≥ 30 时要在 Manifest 加 `<queries><package android:name="com.termux"/></queries>`，否则根本找不到 Termux 的 `RunCommandService`；
3. 用 `RUN_COMMAND` Intent 让 Termux 执行 `ssh -N -L 127.0.0.1:<port>:127.0.0.1:<remotePort> <alias>`（`EXTRA_COMMAND_PATH`、`EXTRA_ARGUMENTS`，后台命令用 `EXTRA_BACKGROUND=true`，可选 `EXTRA_PENDING_INTENT` 拿退出码）；
4. App 只连本地端口，永远不见私钥。

代价：强依赖用户装 Termux 并授权，后台保活也受 Termux 限制；本仓库 App 选择内嵌 JSch 正是为了避开这个依赖（见 `docs/architecture.md` §1）。

### 5.7 G. 为什么不建议 `/sdcard/.ssh`

* Android 11+ 上 `ACTION_OPEN_DOCUMENT_TREE` 不允许选存储根/Download 根，点号目录在 DocumentsUI 里默认不可见；
* Termux 的 `~` 不是 `/sdcard`，`~/.ssh` 与 `/sdcard/.ssh` 是两个地方，容易配错；
* 私钥放在共享存储 = 任何有“所有文件访问”的 App、ADB、备份、云同步都可能拿到。仅适合临时迁移（方案 D）。

### 5.8 H. root/ADB（仅调试）

```bash
adb root && adb pull /data/data/com.termux/files/home/.ssh ./ssh-copy
# 或手机上 su -c 'cat /data/data/com.termux/files/home/.ssh/id_ed25519'
```
非 root 的 `adb pull` / `run-as com.termux` 对正式版 Termux 无效（非 debuggable）。不要把 root 当产品方案。

---

## 6. 本仓库 App 的落地建议（JSch + 加密存储，v0.2.0）

对应代码与规格：`app/src/dev/dsh/remotehosts/core/SshKeyManager.java`、`SshKeyRecord.java`、
`HostStore.java`（密钥库/迁移）、`docs/app-spec.md` §8、`docs/architecture.md` §4。

1. **导入而非读取**：`identityFiles` 只展示“原 config 里写了哪个 IdentityFile”，并在 UI 提示
   “手机无法读取该路径，请在 SSH 密钥管理里生成/导入密钥后选择”。不要尝试把 `~/...` 解析成真实路径。
   * 若需要“App 自己的 `.ssh`”，用 `context.getFilesDir()/.ssh/`（config、known_hosts 等可放这里；
     私钥仍走密钥库加密），不必也不应该去对齐 Termux 的路径。
2. **密钥模型（v0.2.0）**：App 自带密钥库（`sshKeys[]`，与主机凭证同一个 `hosts.enc` 加密文件）。
   * 生成：JSch `KeyPair.genKeyPair` 生成 RSA 2048/3072/4096，私钥 PEM 落在加密存储；
   * 导入：接受 OpenSSH 新格式（`BEGIN OPENSSH PRIVATE KEY`）、PEM PKCS#1/PKCS#8，以及带口令的版本；
     可选公钥行做**配对校验**，不匹配则拒绝（不会保存不可用密钥）；
   * 主机只存 `keyId` 引用；主机编辑页**不再有**粘贴私钥的输入框；
   * 旧的 v0.1.0 内联私钥在加载时自动迁入密钥库（按内容去重、幂等），并清空主机上的旧字段。
3. **算法兼容性（重要）**：
   * App **生成的是 RSA**（默认 3072），兼容性最好；
   * 导入的 **Ed25519 能否使用取决于运行时 JCA/Conscrypt 与系统版本**（mwiede JSch 支持解析格式，
     但不代表系统能完成认证）；不确定时用 RSA/ECDSA；
   * 指纹是 `SHA256:` + 标准 base64(SHA-256(公钥 blob))、无 `=` 填充，与 `ssh-keygen -lf` 的
     SHA256 输出**完全一致**，可直接用于和服务器核对公钥是否装对。
4. **不要加的权限**：`READ/WRITE_EXTERNAL_STORAGE`、`MANAGE_EXTERNAL_STORAGE`。现有 Manifest 只保留
   网络 + 前台服务 + 通知权限，这是正确的；生成/保存密钥不需要额外权限。
5. **可选增强 / roadmap**（按需，**当前未实现**）：
   * 接收 `ACTION_SEND`（方案 5.5），让用户能从 Termux/文件管理器“分享”文件进来；
   * `ACTION_OPEN_DOCUMENT_TREE` 导入（方案 5.2.1），并在引导页写清 Termux 侧
     `ln -s ~/.ssh ~/dsh_ssh` 的步骤；
   * 公钥导出文件 / 二维码（当前只能复制公钥文本）；
   * 客户端侧对导入密钥做更多格式提示（当前已校验可解析、口令、公私钥匹配）。
6. **安全不变量**（与 spec 一致）：私钥/密码/口令/token 不进日志、不进通知、不进异常 message、
   不进 Intent；`HostConfig.toString()` 与 `SshKeyRecord.toString()` 均已排除秘密；私钥剪贴板复制在
   API 33+ 标记 `ClipDescription.EXTRA_IS_SENSITIVE`。

---

## 7. 安全清单

- [ ] 私钥有口令；`chmod 600`；`~/.ssh` 700；`authorized_keys` 600。
- [ ] **每台设备/每个 App 独立密钥，不跨设备/跨应用复制同一私钥**；丢失只吊销单把（v0.2.0 推荐 App 内生成）。
- [ ] App 里的密钥只通过「SSH 密钥管理」查看/复制；公钥可公开，私钥复制后立即清空剪贴板。
- [ ] 手机做 sshd 时只监听局域网/组网，关闭密码登录，改公钥；不用 22 端口做公网映射。
- [ ] 不在共享存储/聊天软件/邮件/云盘留私钥；迁移完删除临时文件。
- [ ] App 侧：加密存储（Keystore 密钥不可导出）、TOFU 主机密钥 + 变更告警、token 只在浏览器 URL 出现一次。
- [ ] 屏幕锁/生物识别保护 App；开启“允许未知来源安装”仅在侧载时临时打开；不要用与电脑共用的私钥。

---

## 8. 故障排查速查

| 场景 | 检查 |
|---|---|
| Termux 里 `ssh` 找不到 | `pkg install openssh`；`which ssh` |
| 手机 sshd 连不上 | `sshd` 是否在跑（`pgrep sshd`）；端口 8022；同网段；路由器 AP 隔离 |
| 锁屏后断 | 电池优化白名单 + `termux-wake-lock` + 关闭 Phantom 进程限制 |
| App 密钥导入失败 / 认证失败 | 私钥/公钥是否配对（`ssh-keygen -y -f key` 对比 `.pub`）；是否有口令但导入时未填/填错；远端 `authorized_keys` 是否 600；是否用了 ed25519 而系统不支持（改用 App 生成的 RSA） |
| 删除密钥后主机连不上 | 主机引用的 `keyId` 已失效 → SSH 密钥管理里重新生成/导入，主机编辑页重新选择 |
| 升级到 v0.2.0 后找不到旧私钥 | 正常会自动迁移（密钥来源显示“迁移”）；若迁移未发生/数据被清，重新生成/导入密钥并重选 |
| App 连上但隧道 401/空白 | 远端 `dsh web` 的 `remotePort`/`token` 是否对；本地端口转发目标应是远端 `127.0.0.1:<remotePort>` |
| 主机密钥变更 | 确认远端是否重装；清除 App 内该主机的信任记录后重连 |

---

## 9. 参考

- Termux Wiki — Remote Access（客户端/服务端、8022、`ssh-copy-id`、`sshd_config`）：<https://wiki.termux.com/wiki/Remote_Access>
- Termux Wiki — Installation / Google Play 现状：<https://wiki.termux.dev/wiki/Installation>、<https://wiki.termux.dev/wiki/Termux_Google_Play>
- Termux Wiki — Termux:Boot：<https://wiki.termux.com/wiki/Termux:Boot>
- Termux Wiki — Termux-services：<https://wiki.termux.dev/wiki/Termux-services>
- Termux Wiki — Sharing Data（`termux-open`、`allow-external-apps`）：<https://wiki.termux.dev/wiki/Sharing_Data>
- Termux Wiki — Internal and external storage：<https://wiki.termux.dev/wiki/Internal_and_external_storage>
- Termux — RUN_COMMAND Intent：<https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent>
- Termux — `TermuxDocumentsProvider`（SAF，根 = `$HOME`）：<https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/filepicker/TermuxDocumentsProvider.java>
- Termux — AndroidManifest（provider/service/权限声明）：<https://github.com/termux/termux-app/blob/master/app/src/main/AndroidManifest.xml>
- AOSP — Application Sandbox：<https://source.android.com/docs/security/app-sandbox>
- Android — App-specific storage / 沙箱：<https://developer.android.com/training/data-storage/app-specific>
- Android — Scoped storage（Android 11）：<https://developer.android.com/about/versions/11/privacy/storage>
- Android — Storage Access Framework：<https://developer.android.com/guide/topics/providers/document-provider>
- Android — All files access（`MANAGE_EXTERNAL_STORAGE`）：<https://developer.android.com/training/data-storage/manage-all-files>
- Android — 后台/前台服务限制：<https://developer.android.com/develop/background-work/services/fgs>
- Android — Local Network Permission（Android 16/17 的局域网权限，Termux Play 版踩过坑）：<https://developer.android.com/privacy-and-security/local-network-permission>
- Phantom Process Killer 说明（Termux 社区整理）：<https://github.com/agnostic-apollo/Android-Docs/blob/master/en/docs/apps/processes/phantom-cached-and-empty-processes.md>
- OpenSSH `ssh(1)` / `ssh_config(5)`：<https://man.openbsd.org/ssh.1>
