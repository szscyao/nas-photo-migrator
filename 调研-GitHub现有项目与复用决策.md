# 调研：GitHub 现有项目盘点与复用决策

> 项目：Android 原生 App「相册逐张边传边删迁移 NAS 工具」（Kotlin / minSdk 29 / SMB + WebDAV）
> 调研时间：2026-11-05（Asia/Hong_Kong）
> 调研方式：`web_search` 检索 + `web_fetch` 逐条打开仓库页面、GitHub REST API、Maven Central 目录、JitPack 构建接口、项目构建脚本原文。
> **所有 Star 数、最近提交时间均取自 GitHub API 在 2026-11-05 的实际返回值，不是估计值。凡本次未验证的内容，一律标注「未核实」。**

---

## 0. 证据判定标准（先读这一节）

| 标记 | 含义 |
|---|---|
| ✅ 已核实 | 我在本次调研中亲自 fetch 到页面/接口/文件原文 |
| ⚠️ 部分核实 | 只核实了间接证据（如依赖清单、第三方说明），核心实现未逐行阅读 |
| ❌ 未核实 | 本次没有拿到证据，文中不给出具体数字或结论 |
| 🚫 检索为空 | 按关键词检索后确实没有找到对应项目 |

**特别声明：下列所有项目的「核心流程」判断，凡是我没有读到源码的，都标 ⚠️ 并写明判断依据。没有一处是我凭印象编造的。**

---

## 1. 结论先行（TL;DR）

1. **没有任何一个现成开源 Android 项目实现「上传→哈希校验→删本地→下一张」的串行闭环。** 检索到的 7 个相关项目里，删除语义最接近的是 SMBSync2 的 `Move` / `Archive` 模式（复制成功后删除源端），但它的「一致」判定只到 **文件名 + 大小 + 修改时间**，没有内容哈希校验 —— 而这正是我们产品的核心铁则，必须自研。
2. **协议库可以也应该直接用**：SMB 用 `eu.agno3.jcifs:jcifs-ng:2.1.10`（MaterialFiles、Amaze 两个万星级 App 都在用这个确切版本，交叉验证成立）；WebDAV 用 `com.github.thegrizzlylabs:sardine-android:0.9`，**只在 JitPack**，Maven Central 确认不存在。
3. **禁止 fork 别人完整 App 冒充自研。** 上表项目里 MaterialFiles / AmazeFileManager 是 **GPL-3.0**、Nextcloud 是 **GPL-2.0**，直接 fork 进闭源商业 App 属侵权；且它们的形态（文件管理器 / 云盘同步）与「迁移并删除」的产品语义不同，照抄会把别人的技术债变成我们的。
4. 我们只做两件事：**引入协议库** + **借鉴架构思想**（NIO Provider 抽象、同步状态库、镜像安全阀、Move/Archive 语义）。

---

## 2. 逐项目分析

### 2.1 nextcloud/android —— WebDAV + AutoUpload 的工业级参考

| 项 | 内容 |
|---|---|
| 仓库地址 | <https://github.com/nextcloud/android> ✅ |
| Star / 活跃度 | **5674 stars**，2059 forks；最近 push **2026-11-04**（极活跃）✅ |
| 语言 / 许可证 | Kotlin（含大量遗留 Java），**GPL-2.0** ✅ |
| 构建关键参数 | `minSdk = 28`、`compileSdk = 37`、`targetSdk = 36`、JVM 21、Compose + ViewBinding 双开 ✅（读自 `app/build.gradle.kts`） |

**技术栈（✅ 读自构建脚本原文）**

- 依赖注入 Dagger 2（`libs.dagger`、`libs.dagger.android`）
- Room（`libs.room.runtime` + KSP）
- **WorkManager**（`libs.work.runtime`、`libs.work.runtime.ktx`）—— 自动上传的后台执行底座
- WebDAV 走两条腿：
  - `com.github.nextcloud:android-library`（`libs.android.library`，**仅 JitPack 发布**），该库 v2 明确写着 *"v2 is using OkHTTP and DAV4jvm by BitfireAT"* ✅（读自 android-library `README.md`）
  - 遗留 `libs.jackrabbit.webdav`，脚本注释写明 *"remove after entire switch to lib v2"* ✅ —— 说明 Nextcloud 自己正在**从 Apache Jackrabbit 迁到 OkHttp + dav4jvm**，而不是用 sardine

**核心流程（⚠️ 部分核实）**

- 自动上传（AutoUpload）是「把本地文件夹的上传副本推到服务器」的语义，由后台 worker 驱动；
- **应用内没有「上传成功后删除本地原图」的流程** —— 这是产品语义决定的（Nextcloud 是双向网盘，删本地会引发同步歧义）。本次未读上传实现源码，故标 ⚠️；但这正是我们与它最本质的分歧点。
- 串行性：未核实（其上传器支持多任务并发队列，非串行）。
- 校验：未核实服务端校验细节（Nextcloud 服务端有 chunked upload + checksum 扩展，但本次未取到证据，不写结论）。
- 断点续传：未核实。

**可借鉴的架构**

1. **`ClientFactory` / `Account` 抽象**：把「服务器地址 + 认证 + 客户端实例」收敛成工厂，UI 层永远不碰协议对象 —— 我们的 `RemoteTarget`（SMB / WebDAV 两实现）就该这么设计。
2. **AutoUpload 的设置模型**：仅 Wi-Fi、仅充电、仅新增文件、指定文件夹白名单 —— 我们做「迁移任务」时可平移这套约束位。
3. **Room 记录每条上传的状态机**（pending / uploading / done / failed + 重试次数）—— 这就是断点记录表的雏形。
4. **Dagger 多模块分层**（app / appscan / 独立 library），协议层与 UI 层物理隔离。

**可复用库（Maven 坐标）**

- `com.github.nextcloud:android-library:<ver>`（JitPack，MIT）—— **不建议用**：它的 API 是 Nextcloud OCS/WebDAV 混合，不是纯 WebDAV，绑死 Nextcloud 服务端。
- 其技术选型可复用：`at.bitfire.dav4jvm`（见 2.8）。

**不建议照抄 / fork 的原因**

- **GPL-2.0**：fork 后衍生作品必须 GPL 开源，闭源商业发行直接违规。
- 仓库体积 626 MB、2000+ forks、1549 个 open issue，代码量远超我们需求（我们要的是 3 个界面的迁移工具）。
- 与 Nextcloud 服务端强耦合（OCS API、能力协商、分享、活动流），砍到只剩 WebDAV 的成本 > 自己写。

---

### 2.2 zhanghai/MaterialFiles —— 双 SMB 栈并存的最佳实证（强烈推荐精读）

| 项 | 内容 |
|---|---|
| 仓库地址 | <https://github.com/zhanghai/MaterialFiles> ✅ |
| Star / 活跃度 | **9390 stars**，784 forks；最近 push **2026-10-15**（活跃）✅ |
| 语言 / 许可证 | Kotlin，**GPL-3.0** ✅ |
| 构建关键参数 | `minSdk 23`、`compileSdk 37`、`targetSdk 36`、Java 11 + core library desugaring ✅ |

**技术栈（✅ 读自 `app/build.gradle` 原文，这是本次调研信息量最大的一个文件）**

- **SMB 同时引入两个库**：
  - `eu.agno3.jcifs:jcifs-ng:2.1.10`（排除 `org.bouncycastle:bcprov-jdk18on`）
  - `com.hierynomus:smbj:0.11.5`（**刻意锁旧版**，注释原文：*"SMBJ 0.12.0 broke anonymous authentication: hierynomus/smbj#792, and it's still an issue in 0.14.0"*，并排除 `bcprov-jdk15on` 换成 `bcprov-jdk15to18`）
  - 配套 `org.slf4j:slf4j-android:1.7.36`（注释：*"Also a dependency of jCIFS-NG"*）—— **jcifs-ng 在 Android 上必须自带 slf4j 实现，否则启动即报 "No SLF4J providers"**
- **WebDAV 用 dav4jvm，不用 sardine**：`com.github.bitfireAT:dav4jvm:02fe1a95...`（JitPack，锁 commit），注释解释为什么锁 commit（要某个修复、不要某个回归）
- 文件系统抽象：**Java NIO2 `FileSystemProvider`** —— `provider/smb`、`provider/webdav`、`provider/ftp`、`provider/sftp` 各自实现一套 `Path / FileSystem / FileAttributeView`（✅ 目录列表已核）

**核心流程（✅ 从目录与 README 可判定）**

- 它是**文件管理器**：`filejob` / `fileaction` 包做复制、移动、删除，前台服务 + 通知显示进度。
- **不是串行迁移引擎**：多任务可并发提交；没有「校验后删除源」的语义（删除是用户显式操作，与传输无因果关系）。
- 校验：无内容哈希（符合文件管理器定位）。
- 断点续传：未核实（未见 resume 相关代码）。

**可借鉴的架构（价值最高）**

1. **NIO2 Provider 抽象**：`SmbFileSystem` / `WebDavFileSystem` 统一成 `java.nio.file.Path`，上层业务（复制任务、进度、异常）只写一遍。我们的 `SmbTarget` / `WebDavTarget` 应照此抽象出 `RemoteFs` 接口（`open()/write()/stat()/delete()`），而不是在业务里写 `if (isSmb)`。
2. **SMB 连接复用**：单例 `SMBClient` + `Session` 复用 + `Share` 按需打开，避免每张照片重连（NAS 的 SMB 握手成本极高，逐张重连会让「逐张迁移」慢到不可用）。
3. **异常归一**：`DavExceptionExtensions.kt`、SMB 异常到 NIO 异常的映射层 —— 我们要把 SMB/WebDAV 的错误统一成「可重试 / 不可重试 / 认证失败 / 磁盘满」，这是断点续传的前提。
4. **BouncyCastle 冲突治理**：它踩过的坑（jcifs-ng 带 jdk18on、smbj 带 jdk15on、Jetifier 不认高版本字节码）我们一定会踩，直接抄它的 `exclude` 策略。

**可复用库（Maven 坐标）**

- `eu.agno3.jcifs:jcifs-ng:2.1.10`（Maven Central）
- `com.hierynomus:smbj:0.11.5`（Maven Central，备用）
- `com.github.bitfireAT:dav4jvm:<tag>`（JitPack）
- `org.slf4j:slf4j-android:1.7.36`（Maven Central）

**不建议照抄 / fork 的原因**

- **GPL-3.0**，且它自己就是「正确实现 NIO2」的重型工程（16 MB 源码、原生 C/CMake、Shizuku、root、归档、FTP 服务器……），我们 90% 的代码用不上。
- 它的产品前提是「用户手动点复制」，我们要的是「无人值守串行迁移 + 自动删除」，状态机与失败恢复策略完全不同。
- 双 SMB 栈是为兼容性兜底，我们不需要同时背两个库的体积与 BC 冲突。

---

### 2.3 egdels/SambaLite —— 与我们技术栈重合度最高的「SMB + 加密存储 + WorkManager」样本

| 项 | 内容 |
|---|---|
| 仓库地址 | <https://github.com/egdels/SambaLite> ✅ |
| Star / 活跃度 | **154 stars**，16 forks；最近 push **2026-11-04**（活跃）✅ |
| 语言 / 许可证 | Java 11+，**Apache-2.0**（对我们最友好的许可证）✅ |
| 构建关键参数 | `minSdk 28`、`compileSdk 36`、`targetSdk 36` ✅ |

**技术栈（✅ 读自 `app/build.gradle` 与 README 原文）**

- 架构：**MVVM + Repository**，Dagger 2 注入
- SMB：**`com.hierynomus:smbj` 0.14.0**，但**不是直接依赖**，而是 `implementation project(':smbj-patch')` —— 本地 patch 模块，注释写明 *"patched for AES-256 encryption"* ✅（**这是 smbj 在 Android 上的真实坑：AES-256 需要自己打补丁**）
- 加密存储：**`androidx.security:security-crypto:1.1.0`** ✅ —— 与我们阶段1选型完全一致，且它在生产使用
- 后台：`androidx.work:work-runtime:2.11.2`；状态库：`androidx.room:room-runtime:2.8.4`（存 sync state）
- 其他：Biometric 1.1.0、Timber、LeakCanary

**核心流程（✅ 读自 `docs/sync_user_guide.md` 原文，写得非常细）**

- 文件夹同步：Local→Remote / Remote→Local / 双向；WorkManager 周期调度（15 分钟 ~ 24 小时），**应用退出、划掉后台、重启后仍继续**（WorkManager 持久化）
- 跳过策略：**仅比较「大小 + 修改时间」**，相同则跳过 —— **没有内容哈希** ✅
- 时间戳保留：上传成功后把远端 mtime 设为本地 mtime（best-effort）
- 删除语义：默认**永不删除任何一侧文件**；可选的 Mirror Mode **只删目标侧**，且只删「自己同步过的」条目，还带三重安全阀：
  - 源列表枚举不完整 → 跳过本轮清扫
  - 源列表为空但历史有条目 → 跳过（防空盘洗库）
  - 计划删除数 > 已跟踪条目 50% **且** > 100 条 → 中止并记日志
  - 删除默认进 `<target>/.sambalite-trash/<timestamp>/` 回收目录
- 断点续传：❌ 无（文档明确写 "Large files may take longer than the WorkManager execution window. Very large files might not complete in a single sync cycle."）
- 额外能力：NAS 发现（子网 445/139 端口扫描 + mDNS/NsdManager `_smb._tcp`）

**可借鉴的架构（对我们价值极高）**

1. **sync state 数据库**：每条「已传输文件」都落库（路径 + 大小 + mtime + 时间戳），这是幂等重跑与「不重复上传」的基础 —— 我们的断点记录表可直接采用这个字段设计，但**必须加一列 `sha256`**（它没有）。
2. **三重安全阀**：我们「上传后删本地」是破坏性操作，**必须**抄这套阈值保护（尤其是「空列表保护」和「删除比例阈值」），并额外加「校验失败绝不删」。
3. **回收目录而非硬删**：`.sambalite-trash/` 的思路可以改成我们的「误删缓冲」——先移入本地回收目录，N 天后再物理删。
4. **SAF 回收站陷阱**：文档专门警告 Android 11+ 上「删除」可能只是移入系统回收站，文件仍被 SAF 枚举到 —— 我们删本地相册时同样会遇到（MediaStore 的 `IS_TRASHED`），必须显式处理，否则「删了但没删掉」会导致无限重复上传。

**不建议照抄 / fork 的原因**

- 它是**同步器**（保留两端、双向、冲突用 "Newer Wins"），我们是**迁移器**（单向、删源、不可回头）。语义相反，硬改会把它的冲突解决逻辑全废掉。
- Java + Dagger + 自绘 UI，与我们的 Kotlin + Compose + Coroutines 不同构。
- 依赖 patched smbj 子模块，我们若不用 smbj 则整套 SMB 层都用不上。

---

### 2.4 Sentaroh/SMBSync2 —— 语义最接近（Move / Archive），但技术栈已过时

| 项 | 内容 |
|---|---|
| 仓库地址 | <https://github.com/Sentaroh/SMBSync2> ✅ |
| Star / 活跃度 | **376 stars**，73 forks；最近 push **2022-07-13**（**已停更 3 年+**）✅ |
| 语言 / 许可证 | Java，**MIT**（最宽松）✅ |
| 构建关键参数 | `minSdkVersion 21`、`compileSdkVersion 29`、`targetSdkVersion 29` ✅ |

**技术栈（✅ 读自 `SMBSync2/build.gradle` 与 `README.MD`）**

- 依赖是**本地 jar 硬塞**：`libs/jcifs-ng-2.1.0-mod-08.jar`、`jcifs-ng-2.1.4-20200413-02.jar`、`jcifs-1.3.17_patch.jar`、`bcprov-jdk15on-158.jar`
- 支撑库还是 `com.android.support:*:28.0.0`（**Support Library，不是 AndroidX**）
- README 的第三方声明确认：包含 **jcifs-ng（LGPL-2.1）** 与 jcifs 1.3.17

**核心流程（✅ 读自 README 原文，这是它最大的价值）**

- 单向 Master → Target，四种模式：
  - `Mirror`：目标端与源端完全一致（源端删了目标端也删）
  - **`Move`：复制到目标成功后，删除源端文件/文件夹（Move 语义）** ← **这就是我们「边传边删」的语义原型**
  - `Copy`：只复制不删
  - **`Archive`：把源目录中「拍摄于 7 天/30 天前」的 `gif/jpg/jpeg/jpe/png/mp4/mov` 移到目标** ← **这就是我们「相册归档」的语义原型**
- 「差异」判定三条：① 仅一侧存在 ② 大小不同 ③ **修改时间不同**（可设 1/3/5/10 秒容差以兼容 FAT/exFAT）
- **没有内容哈希校验** ✅（README 全文无 checksum/hash）
- 断点续传：❌ 未提及（README/构建脚本均无相关证据，标未核实）
- 触发方式：内置计划任务 + 外部 Intent（Tasker/AutoMagic）

**可借鉴的架构**

1. **Move / Archive 的语义定义**：`Archive` = 「按拍摄时间阈值挑选 + 移动到目标」，几乎就是我们的产品定义，可直接借用它的「时间阈值」参数设计（7 天/30 天 → 我们可做「仅迁移早于 N 天」）。
2. **时间容差**：FAT/exFAT 只有 2 秒精度，NAS 常见 exFAT U 盘 → 我们比对新旧文件时也必须设容差，否则永远判定「不一致」。
3. **任务可被外部触发**（Intent/Tasker）：说明它的迁移任务入口是解耦的 —— 我们的「前台服务 + 通知」也应支持从通知/快捷方式重新触发。

**不建议照抄 / fork 的原因**

- **已停更（2022-07 最后提交）、targetSdk 29**：Google Play 现行要求 targetSdk 35+，直接复用无法上架；且 Support Library 与现代 AGP 不兼容。
- **依赖是 vendored 老 jar（jcifs-ng 2.1.4 / jcifs 1.3.17）**，含已知 SMB1 相关安全问题，且有本地魔改（`-mod-08`），无法追溯。
- **没有哈希校验**，它的「Move」在弱网/半写场景下可能删掉只传了一半的源文件 —— 这正是我们必须自研引擎的理由。

---

### 2.5 thegrizzlylabs/sardine-android —— 我们要引入的 WebDAV 库本体

| 项 | 内容 |
|---|---|
| 仓库地址 | <https://github.com/thegrizzlylabs/sardine-android> ✅ |
| Star / 活跃度 | **394 stars**，83 forks；最近 push **2024-02-14**（**近 2 年无提交，低维护**）✅ |
| 语言 / 许可证 | Java，**Apache-2.0** ✅ |
| 制品形态 | **AAR**，`packaging: aar` ✅（读自 JitPack POM） |

**坐标与仓库（✅ 全部实测）**

- ✅ JitPack 可用：`GET https://jitpack.io/com/github/thegrizzlylabs/sardine-android/0.9/sardine-android-0.9.pom` → **HTTP 200**，返回 `groupId=com.github.thegrizzlylabs`、`artifactId=sardine-android`、`version=0.9`、`packaging=aar`
- ✅ JitPack 构建状态：`GET https://jitpack.io/api/builds/com.github.thegrizzlylabs/sardine-android` → 返回 `"0.9": "ok"`，另有 `v0.9 / master / master-v0.9-gd0af7ae-1` 均为 ok
- ✅ **Maven Central 确认不存在**：`GET https://repo1.maven.org/maven2/com/github/thegrizzlylabs/` → **HTTP 404**
- ✅ **传递依赖（POM 原文）**：
  - `com.squareup.okhttp3:okhttp:4.12.0`（compile）
  - `org.simpleframework:simple-xml:2.7.1`（runtime，已排除 `xpp3` / `stax-api` / `stax`）

**能力与已知短板**

- 提供 `Sardine` / `SardineImpl`，封装 WebDAV 方法：`PUT` / `GET` / `DELETE` / `MKCOL` / `MOVE` / `COPY` / `PROPFIND`（Multi-Status 用 simple-xml 反序列化）
- 认证：Basic / Digest（基于 OkHttp Authenticator）
- **无上传进度回调**（只有 `TransferListener` 级别的字节回调，粒度与可靠性不足以驱动「逐张进度 + 断点」；需自行加 OkHttp `Interceptor` 或 `RequestBody` 包装来算进度）—— 这一点是我们必须自己补的胶水层
- **OkHttp 4.12.0 会被传递引入**：若项目用 OkHttp 5.x，需要 `exclude` 或强制版本，否则出现 `okhttp3.internal` 版本混用崩溃
- **simple-xml 2.7.1 较老**（2013 年版本），在 R8/AGP 8+ 下需 confirm 反射保留规则（`-keep` 其注解类），否则 release 包解析 Multi-Status 失败

**可借鉴的架构**

1. **WebDAV 方法 → 一个 `Sardine` 门面**：我们的 `WebDavRemote` 应只暴露 `put(stream, len, path)` / `stat(path)` / `delete(path)` / `mkcol(path)`，把 HTTP 细节关在门面里。
2. **Multi-Status 解析**：`propfind` 的解析坑（命名空间、编码、`getcontentlength` 为 0）可以直接依赖它，不要自己写 XML 解析。
3. **Path 编码**：中文/emoji 相册名必须正确 percent-encode，sardine 已处理，自研容易漏。

**不建议照抄 / fork 的原因**

- 它是**库**不是 App，没有「App 可 fork」的问题；但**不建议 fork 源码改**：AAR 内部改一行就要自己发版，成本高于 `exclude` + 包装。
- 维护低频（2024-02 后无提交）：必须有 Plan B（见 2.8 dav4jvm 或 OkHttp 自研）。

---

### 2.6 AgNO3/jcifs-ng —— 我们要引入的 SMB 库本体

| 项 | 内容 |
|---|---|
| 仓库地址 | <https://github.com/AgNO3/jcifs-ng> ✅ |
| Star / 活跃度 | **347 stars**，113 forks；最近 push **2026-04-18**（活跃）✅ |
| 语言 / 许可证 | Java，**LGPL-2.1** ✅ |
| 最新版本 | **2.1.10** ✅（Maven Central `maven-metadata.xml`：`<latest>2.1.10</latest>`，`<lastUpdated>20231015083756</lastUpdated>`；目录页显示 2.1.10 各构件发布于 **2023-10-15**） |

**Android 实战证据（✅ 双源交叉验证，这是选它的最强理由）**

- `zhanghai/MaterialFiles` `app/build.gradle`：`implementation ('eu.agno3.jcifs:jcifs-ng:2.1.10') { exclude group: 'org.bouncycastle', module: 'bcprov-jdk18on' }`，并额外引入 `org.slf4j:slf4j-android:1.7.36`（注释指明是 jcifs-ng 需要）
- `TeamAmaze/AmazeFileManager` `gradle/libs.versions.toml`：`jcifs = "2.1.10"` → `jcifs-ng = { module = "eu.agno3.jcifs:jcifs-ng", version.ref = "jcifs" }`，`app/build.gradle` 中 `implementation libs.jcifs.ng`（`//smb`），且仓库声明了 `maven { url "https://jitpack.io" }`
- → **结论：2.1.10 在 Android（minSdk 23 环境）上是生产可用版本，两个万星级项目在用同一个确切版本号。**

**技术定位**

- jCIFS 的清理重写版（"A cleaned-up and improved version of the jCIFS library"，GitHub 描述原文），API 保持 `SmbFile` / `SmbFileInputStream` 风格，2.x 支持 SMB2/SMB3 协商（以官方 README 为准，本次未逐行核实协议矩阵）
- 优点：文件级 API 简单（`new SmbFile(url, auth)` 直接 `openInputStream`），适合「逐张读取 + 逐张写远端」的串行模型；SMB1 兼容性好（老 NAS 多）

**已知 Android 坑（✅ 均来自上述两个项目的实际配置）**

1. **必须有 slf4j 实现**，否则运行时报 `No SLF4J providers were found` 且 SMB 静默失败 → 加 `slf4j-android` 或 `slf4j-nop`
2. **BouncyCastle 冲突**：jcifs-ng 默认拉 `bcprov-jdk18on`，与 Android 上常见的 `jdk15to18`/Jetifier 冲突 → 需 `exclude` 后统一版本
3. **许可证是 LGPL-2.1**：作为**未修改的独立库以 Maven 依赖方式动态链接**使用，闭源 App 可接受；但**不要改它的源码后静态打包**，否则触发 LGPL 的衍生作品义务。建议在 App 的「开源许可」页面标注。

**不建议照抄 / fork 的原因**

- 无需 fork：库本身就是成品，改源码会立刻背上「必须开源修改」的义务。
- 其 `SmbFile` 的 URL 语法（`smb://host/share/path`）与路径转义有坑，照抄它的封装配件容易把 Windows 非法字符/超长路径问题一起抄进来 —— 自己写一层 `SmbPath` 校验更安全。

---

### 2.7 hierynomus/smbj —— 备选 SMB2/3 库（对比取舍）

| 项 | 内容 |
|---|---|
| 仓库地址 | <https://github.com/hierynomus/smbj> ✅ |
| Star / 活跃度 | **828 stars**，196 forks；最近 push **2026-10-21**（活跃）✅ |
| 语言 / 许可证 | Java，SMB2/SMB3 原生实现（描述原文 *"Server Message Block (SMB2, SMB3) implementation in Java"*）；**GitHub API 返回许可证为 `Other`（SPDX `NOASSERTION`），使用前需法务确认** ⚠️ |
| 版本 | Maven Central 最新 **0.15.0**（2026-08-21）；常用旧版 **0.11.5**（2022-02-22）、0.14.0（2024-12-09）✅ |

**Android 实战证据（✅ 两份独立项目）**

- `egdels/SambaLite`：`implementation project(':smbj-patch')`，README 第三方声明写明 **SMBJ 0.14.0**，且为 **AES-256 encryption 打了本地补丁** ✅
- `zhanghai/MaterialFiles`：`implementation ('com.hierynomus:smbj:0.11.5')`，注释明确 **0.12.0 起匿名认证被破坏（issue #792），0.14.0 仍未修复，故锁 0.11.5** ✅

**与 jcifs-ng 的取舍（结论：主 jcifs-ng，smbj 作 fallback）**

| 维度 | jcifs-ng 2.1.10 | smbj 0.11.5 / 0.14.0 / 0.15.0 |
|---|---|---|
| 协议 | SMB1 兼容 + SMB2/3 | 纯 SMB2/SMB3（无 SMB1） |
| API 风格 | `SmbFile` 文件级，最贴合「逐张」模型 | `Session/Share/Directory/File` 句柄级，需自己管生命周期 |
| Android 落地 | MaterialFiles + Amaze 双验证，无 patch | SambaLite **必须 patch AES-256**，MaterialFiles 必须锁 0.11.5 |
| 体积/冲突 | 拉 BC(jdk18on) + slf4j | 拉 BC + **Guava**（MaterialFiles 专门引入 `listenablefuture:9999.0-empty-to-avoid-conflict-with-guava` 来消冲突）+ dcerpc |
| 版本活性 | 2.1.10 停在 2023-10（**库本身近 2 年无新版**） | 0.15.0（2026-08），迭代更勤 |
| 老 NAS/SMB1 | 支持 | **不支持** |

→ **决策：按阶段1锁定用 jcifs-ng 2.1.10**；若后期遇到「SMB3 加密 / 大文件吞吐 / SMB1 老 NAS 之外的兼容问题」，再引 smbj 0.15.0（注意 0.12+ 匿名认证回归问题，需实测）。

**不建议照抄 / fork 的原因**

- 同 jcifs-ng：库不需要 fork；SambaLite 的 `smbj-patch` 做法说明**一旦需要 patch 就等于自己维护一个分支**，这是我们要避免的成本。
- 许可证状态不明（`NOASSERTION`），在法务澄清前不宜作为主依赖。

---

### 2.8 附录 A：bitfireAT/dav4jvm —— WebDAV 的「替代方案 B」（比自研 OkHttp 更省事）

| 项 | 内容 |
|---|---|
| 仓库地址 | <https://github.com/bitfireAT/dav4jvm> ✅ |
| Star / 活跃度 | **104 stars**，23 forks；最近 push **2026-11-02**（活跃）✅ |
| 语言 / 许可证 | Kotlin，**MPL-2.0** ✅ |
| 坐标 | **仅 JitPack**：`com.github.bitfireAT:dav4jvm:<tag|commit>` ✅（README 原文；`repo1.maven.org/maven2/at/bitfire/` 返回 404） |

**为什么值得记一笔**

- 它就是 **DAVx⁵ 的 WebDAV 内核**，Nextcloud Android Library v2 也用它（✅ 见 2.1），MaterialFiles 也用它（✅ 见 2.2）——**三个成熟项目共同背书**。
- 基于 **Ktor `HttpClient`**（可换引擎），`propfind` 返回 `Flow`，原生支持协程 —— 与我们的 Kotlin + Coroutines 天然契合。
- 短板：MPL-2.0 是**文件级 copyleft**（改它的文件必须开源该文件，链接使用无碍）；JitPack 版本需锁 tag/commit；引入 Ktor 会增加体积与方法数。

→ **定位：sardine-android 的一级备选**。若 sardine 的 OkHttp 版本冲突或进度回调改造过痛，直接换 dav4jvm，而不是从零写 WebDAV。

---

### 2.9 附录 B：TeamAmaze/AmazeFileManager —— 反面教材 + 版本交叉验证

| 项 | 内容 |
|---|---|
| 仓库地址 | <https://github.com/TeamAmaze/AmazeFileManager> ✅ |
| Star / 活跃度 | **6454 stars**，1712 forks；最近 push **2026-10-31**（活跃）✅ |
| 语言 / 许可证 | Kotlin/Java，**GPL-3.0** ✅ |
| SMB 实现 | `jcifs-ng 2.1.10` + `sshj 0.40.0`(SFTP) + `commons-net`(FTP)，仓库声明 `maven { url "https://jitpack.io" }` ✅ |

**它的价值**

1. **再次印证 jcifs-ng 2.1.10 是 Android 上的事实标准版本**（与 MaterialFiles 完全一致）。
2. **反面教材**：MaterialFiles 的 README 公开点名批评它的 `HybridFile.java` —— *"inventing a custom model for file information/operations, which often gets coupled with UI logic and grows into a mixture of everything"* ✅（README 原文，附了 GitHub 链接）。**这正是我们必须在架构上避免的：协议模型不能和 UI/业务耦合成一个 Hybrid 上帝类。**
3. 它的 `file_operations` 独立 module 是把「文件操作」拆出去的正向尝试，可参考模块划分，但 GPL-3.0 不能抄代码。

---

## 3. 横向对比：四个核心流程维度的真实覆盖情况

| 项目 | 串行（一张一张） | 上传后**内容校验** | 校验通过后**删本地** | **断点续传** |
|---|---|---|---|---|
| nextcloud/android | ❌ 并发队列 | ⚠️ 未核实 | ❌ 无此语义 | ⚠️ 未核实 |
| MaterialFiles | ❌ 多任务并发 | ❌ 无 | ❌ 无（删除是用户显式操作） | ❌ 未见实现 |
| SambaLite | ⚠️ 单任务顺序遍历（非严格串行闭环） | ❌ 仅 size+mtime | ❌ 默认不删；Mirror 只删**目标端** | ❌ 文档明说大文件可能一个窗口传不完 |
| SMBSync2 | ✅ 任务内串行 | ❌ 仅 name/size/mtime（带时间容差） | ✅ **Move 模式复制成功后删源** | ⚠️ 未核实 |
| sardine-android | —（库） | — | — | — |
| jcifs-ng | —（库） | — | — | — |
| smbj | —（库） | — | — | — |

**结论：全世界（本次检索范围内）没有一个开源 Android App 同时满足我们的四条铁则。**
`Move/Archive` 语义（SMBSync2）❌ 无哈希；`哈希校验`（工业界做法）❌ 无人做；`断点续传` ❌ 无人在「逐张迁移」场景做。
→ **「上传 → 校验 → 删本地 → 下一张」的串行迁移引擎必须 100% 自研，这不是重复造轮子，这是我们的护城河。**

---

## 4. 依赖 / 自研决策表

| # | 依赖名 | 类型 | 决策与理由 | 证据 |
|---|---|---|---|---|
| 1 | **SMB 客户端：`eu.agno3.jcifs:jcifs-ng:2.1.10`** | ✅ **直接引入 Maven 坐标（Maven Central）** | 唯一被两个万星级 Android 项目同时验证的 SMB 库；`SmbFile` 文件级 API 与「逐张读写」模型天然契合；支持 SMB1 兼容 + SMB2/3。**必须配套**：`org.slf4j:slf4j-android:1.7.36` + `exclude org.bouncycastle:bcprov-jdk18on` | Central `<latest>2.1.10</latest>`；MaterialFiles `app/build.gradle`；Amaze `libs.versions.toml`（`jcifs = "2.1.10"`） |
| 2 | **WebDAV 客户端：`com.github.thegrizzlylabs:sardine-android:0.9`** | ✅ **直接引入 Maven 坐标（⚠️ 仅 JitPack）** | 纯 WebDAV 门面，`PUT/PROPFIND/MKCOL/DELETE` 齐全，免写 Multi-Status 解析。**必须在 `settings.gradle.kts` 的 `dependencyResolutionManagement.repositories` 加 `maven("https://jitpack.io")`（并建议配 `content { includeGroup("com.github.thegrizzlylabs") }` 限定范围，避免全量走 JitPack）**。传递依赖 `okhttp 4.12.0` + `simple-xml 2.7.1` 需 `exclude`/强制版本，并加 R8 keep 规则 | JitPack POM 200（`packaging=aar`，deps okhttp 4.12.0 / simple-xml 2.7.1）；JitPack builds API `"0.9":"ok"`；`repo1.maven.org/maven2/com/github/thegrizzlylabs/` **404** |
| 3 | **WebDAV 替代方案 A：`com.github.bitfireAT:dav4jvm`（JitPack）** | ⏸ **备选，不进 v1 依赖** | 若 sardine 的 OkHttp 版本冲突 / 进度改造过痛，直接换它（DAVx⁵ + Nextcloud + MaterialFiles 三家在用，Ktor + Flow，协程原生）。**不选它做首选**：多引一个 Ktor 栈，且需锁 commit | dav4jvm 104★ / push 2026-11-02 / MPL-2.0；README 安装段；`repo1.maven.org/maven2/at/bitfire/` 404 |
| 4 | **WebDAV 替代方案 B：OkHttp 自研 WebDAV 客户端** | ⏸ **兜底方案（工作量约 1.5~2 人日）** | 只在 JitPack 不可用 / 两个库都不满足时启用。需自写：`PROPFIND`(Depth:1) XML 解析、`PUT`(带 `Content-Length`)、`MKCOL`、`HEAD`/`PROPFIND` 取 `getcontentlength`、Basic/Digest 认证、429/507 错误映射 | 我们已在阶段1评估；sardine 的源码即参考实现（Apache-2.0 可读不可抄） |
| 5 | **「上传→校验→删本地」串行迁移引擎** | 🛠 **必须自研（核心铁则，绝不复用）** | **无任何现成库/App 提供此语义**（见 §3 表格）。必须自研的关键点：单线程串行状态机、逐张 `SHA-256`（或 CRC32C）边传边算、远端回读校验（SMB `stat` 大小 + 抽样回读哈希 / WebDAV `PROPFIND` 的 `getcontentlength` + `getetag`）、删除前的「三重确认」、失败即停并回滚到「未删」状态 | §3 对比表；SMBSync2 README（Move 无哈希）；SambaLite sync guide（仅 size+mtime） |
| 6 | **MediaStore 相册访问（含 Android 11+ 分区存储 / `IS_TRASHED`）** | 🛠 **必须自研** | 无库可替。要点：`MediaStore.Images.Media` 分页游标（`_ID > lastId`）、`RELATIVE_PATH`、`DATE_TAKEN`、`SIZE`、`MIME`；删除走 `MediaStore.createDeleteRequest()`（Android 11+ 需用户确认）或 `ContentResolver.delete`；**必须处理「删除只是进回收站」**（SambaLite 文档专门警告过此坑） | SambaLite `docs/sync_user_guide.md`「Android trash / recycle bin caveat」 |
| 7 | **前台服务 + 常驻通知 + 断点记录** | 🛠 **必须自研** | 迁移是长事务，必须 `ForegroundService`（`foregroundServiceType="dataSync"`）+ 通知进度条 + `WakeLock`；断点记录用 Room 表（`assetId, remotePath, size, sha256, state, attempt, updatedAt`），字段设计参考 SambaLite 的 sync state + 我们新增 `sha256` | SambaLite：`work-runtime` + `room-runtime` 存 sync state；MaterialFiles：`filejob` + 前台服务通知 |
| 8 | **加密存储：`androidx.security:security-crypto:1.1.0`** | ✅ **直接引入（Google Maven）** | 存 NAS 账号/密码/密钥，用 `EncryptedSharedPreferences` + `MasterKey`（AES256_GCM）。**注意**：1.1.0 已从 alpha 转正；Jetpack 官方已把该库标记为「不再新增特性」，长期可评估迁移到自研 Keystore 封装，但 v1 用它最稳 | Google Maven `group-index.xml`：`security-crypto versions="...1.1.0-alpha01...1.1.0"`（**1.1.0 为最新**）；SambaLite `app/build.gradle`：`androidx.security:security-crypto:1.1.0` |
| 9 | **Room** | ✅ 直接引入 | `androidx.room:room-runtime` + `room-ktx` + KSP `room-compiler`。存断点表、任务表、失败重试表 | SambaLite `room 2.8.4`；Nextcloud `libs.room.runtime` |
| 10 | **Compose + Coroutines** | ✅ 直接引入 | UI 与串行引擎的 `Flow` 进度流对接；`kotlinx-coroutines-android` | Nextcloud `libs.compose.*`；MaterialFiles `kotlinx-coroutines 1.11.0` |
| 11 | **WorkManager** | ⏸ **可选，v1 不引入** | 我们的执行体是「用户点开始 → 前台服务串行跑完」，不是「周期性后台任务」。WorkManager 的 10 分钟执行窗口反而会**打断**大文件迁移。v2 若做「定时自动迁移」再引 | SambaLite 明确写「大文件可能超出 WorkManager 执行窗口」——**正是我们不该用它的原因** |
| 12 | **smbj（备选 SMB）** | ⏸ **不进 v1 依赖** | 仅当 jcifs-ng 在目标 NAS 上出现兼容/性能问题时启用；引 0.15.0 需实测匿名认证回归（#792） | Central 版本列表 0.15.0(2026-08-21)；MaterialFiles 注释锁 0.11.5；SambaLite 需 patch AES-256 |
| 13 | **nextcloud/android-library、MaterialFiles、Amaze、SMBSync2、SambaLite 的 App 代码** | 🚫 **不复用（禁止 fork）** | GPL-2.0 / GPL-3.0 / Apache-2.0 但形态不匹配；只借鉴架构思想（见各节「可借鉴」） | 各仓库 LICENSE / GitHub API `license` 字段 |

---

## 5. 红线：禁止 fork 别人完整 App 冒充自研

1. **法律红线**：`nextcloud/android` = GPL-2.0、`zhanghai/MaterialFiles` = GPL-3.0、`TeamAmaze/AmazeFileManager` = GPL-3.0。**fork 后闭源分发 = 侵权**；即便开源，也必须以同许可证开放全部衍生代码，商业上不可接受。
2. **工程红线**：这些 App 都是「通用文件管理器 / 双向同步器」，与「单向迁移 + 删除源」的状态机语义相反。把同步器的冲突解决、镜像逻辑改造成迁移器，改造量 > 重写量，且会继承它们的 1549 / 626 / 667 个 open issue。
3. **诚信红线**：把别人的 App 换个包名、换个图标就宣称自研，属于学术与商业双重不端。本项目对外表述统一为：**「自研串行迁移引擎 + 引入 jcifs-ng / sardine-android 两个开源协议库」**，并在 App 内「开源许可」页完整列出依赖与许可证。
4. **可以做的事**（明确边界）：
   - ✅ 读它们的源码，理解 NIO Provider 抽象、连接复用、错误映射、安全阀设计，然后**用自己的代码实现**；
   - ✅ 引入它们用的**库**（jcifs-ng / sardine / dav4jvm / security-crypto）；
   - ✅ 抄它们的「配置项设计」（如「仅 Wi-Fi」「仅早于 N 天」「时间容差 3 秒」）—— 这是思想，不是代码；
   - ❌ 复制粘贴它们的 `.kt/.java` 文件（GPL 传染）；
   - ❌ fork 整个仓库改包名发布。

---

## 6. 结论

### 6.1 直接依赖清单（可直接写进 `libs.versions.toml`）

| 依赖 | 坐标 | 仓库 | 版本证据 |
|---|---|---|---|
| SMB | `eu.agno3.jcifs:jcifs-ng:2.1.10` | Maven Central | Central `maven-metadata.xml` `<latest>2.1.10</latest>`；`.../2.1.10/` 目录 200（2023-10-15 发布） |
| SMB 日志 | `org.slf4j:slf4j-android:1.7.36` | Maven Central | MaterialFiles `app/build.gradle` 原文注释「Also a dependency of jCIFS-NG」 |
| WebDAV | `com.github.thegrizzlylabs:sardine-android:0.9` | **JitPack**（`https://jitpack.io`） | JitPack POM 200（`packaging=aar`）；builds API `"0.9":"ok"`；Central 同坐标 **404** |
| 加密 | `androidx.security:security-crypto:1.1.0` | Google Maven | `androidx/security/group-index.xml` 中 `security-crypto` 最新为 `1.1.0`；SambaLite 生产在用 |
| 持久化 | `androidx.room:room-runtime` / `room-ktx` / `room-compiler`(KSP) | Google Maven | SambaLite `2.8.4`；Nextcloud `libs.room.runtime` |
| UI/异步 | `androidx.compose.*`（BOM）、`org.jetbrains.kotlinx:kotlinx-coroutines-android` | Google Maven / Central | Nextcloud Compose BOM 用法；MaterialFiles coroutines `1.11.0` |

**构建脚本必须做的三件事（否则编译期/运行期必炸）**

1. `settings.gradle.kts` 加 `maven("https://jitpack.io")`，建议配 `content { includeGroup("com.github.thegrizzlylabs") }`。
2. sardine 的传递依赖处理：`exclude(group = "org.simpleframework", module = "simple-xml")` 需谨慎（Multi-Status 解析要用），**不要排除**；要排除/统一的是 **OkHttp 版本**；并给 simple-xml 加 R8 keep 规则。
3. jcifs-ng 的 BC 冲突：`exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")` + 统一 BC 版本。

### 6.2 必须自研清单

| 自研模块 | 为什么没有现成的 | 验收要点 |
|---|---|---|
| **串行迁移引擎**（上传→校验→删本地→下一张） | §3 已证明：无任何开源 App/库提供此闭环；SMBSync2 的 Move 只比 size/mtime | 单线程、逐张、状态机可恢复；任一步失败**绝不删源**；`SHA-256` 边传边算 + 远端回读确认 |
| **MediaStore 相册枚举与删除** | 无库替代；Android 11+ 有 `createDeleteRequest` / `IS_TRASHED` 陷阱 | 分页游标不漏不重；删除后二次确认 `IS_TRASHED`/`EXISTS` |
| **前台服务 + 通知 + 断点记录** | 无库替代（WorkManager 不适用长任务） | `dataSync` FGS、进度通知、进程被杀后可续跑、Room 幂等表 |
| **协议抽象层 `RemoteFs`** | 借鉴 MaterialFiles 的 NIO 思路，但代码自写 | `open/write/stat/delete/exists` 五方法，SMB 与 WebDAV 各一实现，业务层零 `if (isSmb)` |
| **凭据与配置存储** | 用 `EncryptedSharedPreferences`（库），但「多 NAS 配置 + 测试连接 + 迁移方向」的业务封装自研 | 密码不落明文、不写日志、导出配置不含密码 |

### 6.3 一句话风险提示

- **sardine 0.9 停在 2024-02**，是本次选型里唯一的「低维护风险点」→ 必须保留 dav4jvm（MPL-2.0，活跃）作为一级替代，OkHttp 自研作为兜底。
- **jcifs-ng 2.1.10 停在 2023-10**，但两个万星 App 仍在用同版本 → 风险可接受，需在 App 内做「SMB 连接自检」以便快速定位 NAS 兼容问题。

---

## 7. 本次调研的验证证据索引（可逐条复现）

| # | URL | 用于证明 |
|---|---|---|
| 1 | <https://api.github.com/repos/nextcloud/android> | 5674★、Kotlin、GPL-2.0、push 2026-11-04 |
| 2 | <https://raw.githubusercontent.com/nextcloud/android/master/app/build.gradle.kts> | minSdk 28 / compileSdk 37；WorkManager、Room、Dagger、`libs.android.library`、遗留 jackrabbit 注释 |
| 3 | <https://raw.githubusercontent.com/nextcloud/android-library/master/README.md> | 「v2 使用 OkHTTP + DAV4jvm」；`com.github.nextcloud:android-library` 仅 JitPack |
| 4 | <https://api.github.com/repos/zhanghai/MaterialFiles> | 9390★、Kotlin、GPL-3.0、push 2026-10-15 |
| 5 | <https://raw.githubusercontent.com/zhanghai/MaterialFiles/master/app/build.gradle> | jcifs-ng 2.1.10 + smbj 0.11.5（含 #792 锁版本注释）+ dav4jvm + slf4j-android + BC 排除策略 |
| 6 | <https://api.github.com/repos/zhanghai/MaterialFiles/contents/app/src/main/java/me/zhanghai/android/files/provider> | `provider/smb`、`provider/webdav` 等 NIO 抽象目录 |
| 7 | <https://raw.githubusercontent.com/zhanghai/MaterialFiles/master/README.md> | 对 Amaze `HybridFile` 的架构批评原文（反面教材） |
| 8 | <https://api.github.com/repos/egdels/SambaLite> | 154★、Java、Apache-2.0、push 2026-11-04 |
| 9 | <https://raw.githubusercontent.com/egdels/SambaLite/main/app/build.gradle> | minSdk 28；`:smbj-patch`（AES-256）；`security-crypto:1.1.0`；work 2.11.2；room 2.8.4 |
| 10 | <https://raw.githubusercontent.com/egdels/SambaLite/main/docs/sync_user_guide.md> | 仅 size+mtime 跳过；不删源；Mirror 安全阀；WorkManager 大文件限制；Android 回收站陷阱 |
| 11 | <https://api.github.com/repos/Sentaroh/SMBSync2> | 376★、Java、MIT、push 2022-07-13（停更） |
| 12 | <https://raw.githubusercontent.com/Sentaroh/SMBSync2/2.55/README.MD> | Mirror/Move/Copy/Archive 语义；比较仅 name/size/mtime |
| 13 | <https://raw.githubusercontent.com/Sentaroh/SMBSync2/2.55/SMBSync2/build.gradle> | targetSdk 29、Support Library、vendored jcifs-ng 2.1.4 jar |
| 14 | <https://api.github.com/repos/thegrizzlylabs/sardine-android> | 394★、Java、Apache-2.0、push 2024-02-14 |
| 15 | <https://jitpack.io/com/github/thegrizzlylabs/sardine-android/0.9/sardine-android-0.9.pom> | 0.9 存在、`packaging=aar`、依赖 okhttp 4.12.0 / simple-xml 2.7.1 |
| 16 | <https://jitpack.io/api/builds/com.github.thegrizzlylabs/sardine-android> | `"0.9":"ok"`（JitPack 构建成功） |
| 17 | <https://repo1.maven.org/maven2/com/github/thegrizzlylabs/> | **404**，证实 Maven Central 无此坐标 |
| 18 | <https://repo1.maven.org/maven2/eu/agno3/jcifs/jcifs-ng/maven-metadata.xml> | `<latest>2.1.10</latest>` |
| 19 | <https://repo1.maven.org/maven2/eu/agno3/jcifs/jcifs-ng/2.1.10/> | 2.1.10 构件目录 200（2023-10-15） |
| 20 | <https://api.github.com/repos/AgNO3/jcifs-ng> | 347★、Java、LGPL-2.1、push 2026-04-18 |
| 21 | <https://api.github.com/repos/hierynomus/smbj> | 828★、SMB2/SMB3 描述、license=Other |
| 22 | <https://repo1.maven.org/maven2/com/hierynomus/smbj/> | 版本列表，最新 0.15.0（2026-08-21），含 0.11.5 |
| 23 | <https://api.github.com/repos/bitfireAT/dav4jvm> | 104★、Kotlin、MPL-2.0、push 2026-11-02 |
| 24 | <https://raw.githubusercontent.com/bitfireAT/dav4jvm/main/README.md> | 仅 JitPack 安装；Ktor；`propfind` 返回 Flow |
| 25 | <https://api.github.com/repos/TeamAmaze/AmazeFileManager> | 6454★、Kotlin、GPL-3.0、push 2026-10-31 |
| 26 | <https://raw.githubusercontent.com/TeamAmaze/AmazeFileManager/release/4.0/gradle/libs.versions.toml> | `jcifs = "2.1.10"` 交叉验证 |
| 27 | <https://dl.google.com/dl/android/maven2/androidx/security/group-index.xml> | `security-crypto` 版本列表，最新 `1.1.0` |
| 28 | <https://repo1.maven.org/maven2/at/bitfire/dav4jvm/> | **404**，dav4jvm 不在 Central |

> 复核建议：以上 URL 均为 2026-11-05 实测可访问；Star 数会随时间变化，交付评审时如需最新数字请重新调 API #1 / #4 / #8 / #11 / #14 / #20 / #21 / #23 / #25。
