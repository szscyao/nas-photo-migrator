# 阶段4 交付物：UI 界面（Jetpack Compose + Material 3）

> 单 Activity + Compose Navigation，MVVM（ViewModel + StateFlow + `collectAsStateWithLifecycle`）。
> UI 只观察状态、只调用业务接口，**不包含任何文件传输/校验/删除逻辑**（分层约束见阶段1 1.2）。

---

## 1. 文件清单（`app/src/main/java/com/nasphoto/migrator/ui/`）

| 模块 | 文件 | 说明 |
|------|------|------|
| 入口 | `MainActivity.kt` | 单 Activity；权限申请编排；`NasMigratorTheme { AppNavHost() }` |
| 导航 | `navigation/AppNavHost.kt` | `Scaffold` + 底部导航 5 个目的地（home / nas / settings / failures / logs） |
| 主题 | `theme/Theme.kt`、`theme/Color.kt` | 亮/暗两套 Material3 ColorScheme（indigo/blue），状态栏图标跟随明暗 |
| 首页 | `home/HomeScreen.kt`、`home/HomeViewModel.kt`、`home/HomeCards.kt`、`home/HomeStatus.kt` | 统计 + 状态机展示 + 控制按钮 + 权限卡片 |
| NAS | `nas/NasConfigScreen.kt`、`nas/NasConfigDialog.kt`、`nas/NasConfigViewModel.kt` | 多配置列表/增删改/激活/测试连接 |
| 设置 | `settings/SettingsScreen.kt`、`settings/SettingsSections.kt`、`settings/SettingsViewModel.kt`、`settings/GuideTexts.kt` | 迁移规则、排序、相册筛选、权限/保活、重置记录 |
| 异常 | `failures/FailureScreen.kt`、`failures/FailureViewModel.kt` | 失败列表/重试/删除记录/清空 |
| 日志 | `logs/LogScreen.kt`、`logs/LogViewModel.kt` | 级别筛选、清空、导出/分享 |
| 通用 | `components/CommonComponents.kt`、`util/Formatters.kt`、`util/AppPermissions.kt`、`util/AppContainerAccess.kt`、`util/LogExporter.kt`、`permission/PermissionState.kt` | 卡片/空状态/字节格式化/权限状态/日志导出 |

---

## 2. 页面结构与功能

### 2.1 首页（Home）

1. **目标 NAS 卡片**：当前激活配置（名称 + 协议 + 摘要）；未配置时一键跳转 NAS 页；
2. **权限卡片**（不阻断浏览、阻断开始）：缺相册读权限 / 缺「所有文件访问」/ 缺通知权限，每张卡片带一键跳转；
3. **统计区**：待迁移数量与总大小（开始前预览 `engine.loadPreview()`）、已释放空间（`migrationStore.freedBytesFlow()`）、历史完成数、本次成功/失败/跳过；
4. **运行状态卡**：状态文案、暂停原因、整体进度条、当前文件名 + 单文件进度 + 当前阶段（检查/上传/校验/删除/记录）、重试次数、NAS 剩余空间；
5. **网络/电量标识**：WiFi/移动网络、网络可用性、电量百分比与充电状态；
6. **控制按钮 2×2**：开始（先弹预览确认）/ 暂停 / 继续 / 停止，按状态启用禁用；动作统一走 `MigrationForegroundService.start/pause/resume/stop(context)`，确保后台运行与通知一致；
7. **失败入口**：直接跳转异常管理页。

### 2.2 NAS 配置页

- 列表：名称、协议、`smb://host:port/share/path` 或 `http(s)://host:port/path` 摘要、密码掩码、是否激活（单选）；
- 新增/编辑（全屏 Dialog）：协议切换时端口默认值联动（SMB 445 / WebDAV 80 或 443）、SMB 共享名、远程目录、用户名、密码（`PasswordVisualTransformation`）、WebDAV HTTPS 开关；
- **测试连接**：`NasClientFactory.create(config).use { it.testConnection() }` 在 IO 线程执行，成功显示服务器描述/根路径/可写/剩余空间，失败显示 `NasErrorType.description`；测试期间按钮 loading；
- 删除需二次确认；`NasConfig.sanitized()` 保证密码不显示、不写日志。

### 2.3 设置页

| 分组 | 项 |
|------|-----|
| 迁移规则 | 仅 WiFi 迁移、MD5 完整性校验（附说明：默认大小校验已满足铁则，MD5 更严格但更慢）、低电量自动暂停 + 阈值滑块（5~50，默认 20）、迁移期间保持唤醒 |
| 扫描 | 排序方式（最早优先/最新优先）、相册筛选（全部/仅选中，展开相册多选并显示数量与大小） |
| 权限与保活 | 打开「所有文件访问」、通知权限、电池优化白名单、后台保活说明弹窗（华为/小米/OPPO/vivo 步骤） |
| 危险操作 | 重置已完成迁移记录（二次确认，说明会重新扫描上传但**不会删除本地**） |

### 2.4 异常管理页

- 卡片：文件名、失败分类（`errorType.description`）、详细原因、失败时间、大小、尝试次数、远程路径；
- 单条：重试（清除该 fileKey 的失败记录 → 回首页开始迁移即可重新处理）、查看详情、删除记录；
- 顶部：清空全部（二次确认，明确「只清记录，不删本地文件」）；
- 空状态说明。

### 2.5 日志页

- 时间倒序列表：级别颜色（DEBUG/INFO/WARN/ERROR）、tag、message；
- 级别筛选；清空（二次确认）；
- 导出/分享：写 `cacheDir/logs/*.txt` → `FileProvider.getUriForFile(context, "${packageName}.fileprovider", file)` 分享；FileProvider 异常时降级为纯文本 `ACTION_SEND`（不崩溃）。

---

## 3. 权限流程

```
首次进入首页
 ├─ API 33+：请求 READ_MEDIA_IMAGES + READ_MEDIA_VIDEO + POST_NOTIFICATIONS
 ├─ API 29~32：请求 READ_EXTERNAL_STORAGE
 ├─ API 30+：检查 Environment.isExternalStorageManager()
 │     └─ 未授权 → 卡片引导 Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
 └─ 结果回写 PermissionState（CompositionLocal 下发，各页共享）
```

- 权限状态不阻断浏览，但「开始迁移」前引擎会二次校验（`PermissionGuard`），缺失则暂停并给出 `PauseReason.STORAGE_PERMISSION_LOST`；
- 通知权限缺失时前台服务仍可运行（通知可能不显示），UI 给出提示卡片。

---

## 4. 状态映射（UI ← MigrationState）

| 字段 | 展示 |
|------|------|
| `status` | 顶部状态文案与按钮可用性 |
| `pauseReason.description` | 暂停原因提示条 |
| `totalCount / processedCount / overallProgress` | 整体进度条与「x/y」 |
| `currentFileName / currentFileProgress / currentStage` | 当前文件卡片 |
| `freedBytes` | 已释放空间（实时累计） |
| `remainingBytes` | 预计还可释放 |
| `successCount / failedCount / skippedCount` | 本次会话统计 |
| `nasFreeSpaceBytes` | NAS 剩余空间（null 不显示） |
| `isWifi / isNetworkAvailable / batteryPercent / isCharging` | 顶部小标识 |

---

## 5. 与其它层的接口

- 唯一依赖入口：`(applicationContext as MigratorApp).container`（`AppContainer`）；
- 只使用：`container.engine`（`MigrationEngineApi`）、`container.galleryRepository`、`container.nasConfigRepository`、`container.settingsRepository`、`container.migrationStore`；
- 不直接调用 `NasClient` 实现（除 NAS 配置页的「测试连接」通过工厂创建临时客户端，`use{}` 保证释放）；
- ViewModel 通过 `viewModelFactory { initializer { XxxViewModel(app.container) } }` 构造（无 Hilt，与阶段1「手动 DI」决策一致）。

---

## 6. 显示细节

- 字节格式化：`B / KB / MB / GB`（保留 1~2 位小数）；
- 时间：`yyyy-MM-dd HH:mm`；
- 空状态/加载态/错误态统一组件；
- 深色模式自动适配（`isSystemInDarkTheme()`），状态栏图标颜色跟随；
- 底部导航 5 项（首页/NAS/设置/异常/日志），异常页显示失败数量角标（来自 `failureCountFlow`）。

---

## 7. 验证

| 验证项 | 方式 |
|--------|------|
| 编译（Compose/Material3 API 正确性） | 真实 gradle `assembleDebug`（见阶段5） |
| 5 页导航 | 手工点击验证 / Compose Preview（开发期） |
| 权限流程 | Android 11（需所有文件访问）、Android 13（媒体细分权限）、Android 14/15（前台服务类型）三档真机 |
| 状态展示 | 造假数据（临时改 mock state）走查，或使用真机迁移过程观察 |
| 日志导出 | 真机分享到文件管理器/微信 |

> 说明：UI 层代码不做业务决策，所有铁则行为由阶段3 引擎保证；UI 的任何改动都不会影响「串行执行 / 校验后删除」的正确性。
