# Stage 0 报告：基线冻结、版本收口、IPC 安全

> 日期：2026-10-30
> 分支：`release/4.0`
> 基线：v4.8 `fa244f3` → 内部版本 **3.6.0-internal / 360**
> 提交：`c8dc9bf`（安全/版本/基线）、`397917e`（Stage 0 文档）
> 状态：Stage 0 完成；真机安全验收通过（2026-10-30）

## 一、完成项

### 1. 工作副本与版本标签

- 新工作副本：`D:\project\WorkBuddyData\取件码助手\pickup-code-grabber-4.0`
- 原目录 `D:\project\Xiaomi-HyperOs-pickup-code-grabber` 保持只读，未改动。
- 追溯标签（本地，不推送）：
  - `internal/3.2.0` → `16259e4`
  - `internal/3.3.0` → `ae3bdc6`
  - `internal/3.4.0` → `4abe3d7`
  - `internal/3.5.0` → `4f4b6f4`
  - `internal/3.6.0` → `fa244f3`
- 分支：`release/4.0`。

### 2. 单一版本源

- 新增 `version.properties`：
  - `VERSION_NAME=3.6.0-internal`
  - `VERSION_CODE=360`
- `app/build.gradle` 从 `version.properties` 读取；新增 `checkVersionConsistency`，构建前检查 Manifest 不再硬编码版本。
- `app/AndroidManifest.xml` 删除 `versionCode/versionName/uses-sdk/package` 硬编码，改由 Gradle namespace/defaultConfig 注入。
- 验证：debug/release APK 均为 `360 / 3.6.0-internal`。

### 3. IPC 安全修复

- 新增 `CallerPolicy.java`：
  - `onSms` 仅接受 system(1000)、phone(1001)、`com.android.mms` / `com.miui.mms` / `com.oplus.mms` / `com.coloros.mms` / `com.android.phone` / `com.android.providers.telephony` 及自身 UID；
  - `markDone` 仅接受自身 UID；
  - 拒绝时返回 `rc=-3`，只记录 UID/包名，不打印正文。
- `TodoProvider`：
  - 增加 `Binder.getCallingUid()` 校验；
  - 增加 body ≤ 4000 字、sender ≤ 128 字、未来时间 > 24h 归正；
  - 支持 `miss=true` 直接记录错题本（替代旧 Receiver）；
  - 返回 `rc` 便于诊断。
- 删除 `SmsEventReceiver.java` 与 Manifest 中的 exported Receiver；旧 `ACTION_ON_SMS` 广播入口不存在。
- `NotiRelay`：从广播改为 `ContentResolver.call`，通知通道复用 Provider。
- `Notifier`：内容/已取件动作改为 `NotificationActionActivity`。
- `NotificationActionActivity`（新增，exported=false）：
  - 处理 `copy` / `done`；
  - 校验码形后在后台执行标记；
  - 不再由 `LauncherActivity` 接受外部 `done`/`copy` extra。
- `LauncherActivity`：删除 `handleCopy` / `handleDone` 及 `onNewIntent`/`onResume` 调用。
- `SmsBridge`：删除广播兜底；Provider 异常时进入 `SystemDirectWriter` 兜底。
- 新增 `test/CallerPolicyTest.java`、`test/SecurityChecklist.md`；`tools/run_tests.sh` 改为可移植路径并包含调用方测试。

## 二、构建结果

环境：JDK 17.0.20.1、Android SDK 34、Gradle 8.6、AGP 8.2.2。

| 产物 | 路径 | 大小 | 版本 | 签名 |
|---|---|---|---|---|
| Debug | `app/build/outputs/apk/debug/app-debug.apk` | — | 360 / 3.6.0-internal | debug |
| Release | `app/build/outputs/apk/release/app-release.apk` | 3.4 MB | 360 / 3.6.0-internal | `CN=PickupCodeGrabber` |

Release SHA-256：

```
3cac9576207edaac39682a262e3ed38cb5bf46e3d0601c063fa45e71f9044c4b
```

证书 SHA-256：`fba0e7cc3fae670fc846d99c77a36c130045d4eb8ab28a2a52066551e8b78d8b`

已导出到工作区：

- `D:\project\WorkBuddyData\取件码助手\artifacts\pickup-code-grabber-3.6.0-internal-release.apk`
- 同目录 `.sha256` 校验文件

## 三、测试结果

| 测试 | 结果 |
|---|---|
| FullReg | 20 / 20 PASS |
| ChannelParity | 7 / 7 PASS |
| PkgInputTest | 8 / 8 PASS |
| TierExclusionTest | 21 / 21 PASS |
| CallerPolicyTest | 11 / 11 PASS |
| RulesRegression | 32 / 32 codes，13 / 13 places，0 FAIL |
| UserRulesTest | 45 / 45 PASS |
| 规则三副本一致性 | version=6，anchors=13，全部一致 |

> 控制台中文显示为 GBK 乱码，是终端编码问题；测试计数与退出码正常。

### 真机验收（2026-10-30）

设备：REDMI K90 Pro Max / HyperOS 4.0 / Android 17（SDK 37）。
安装：`adb install -r -d` 覆盖 4.0.0(400)，保留数据；当前安装 `3.6.0-internal(360)`。

| 项 | 结果 |
|---|---|
| 冷启动 | 201ms，六项体检全绿 |
| 未授权 `onSms`（shell uid 2000） | PASS：`rc=-3, error=unauthorized` |
| 未授权 `markDone`（shell uid 2000） | PASS：`rc=-3, error=unauthorized` |
| 旧 `ACTION_ON_SMS` 广播 | PASS：`Broadcast completed: result=0`，无 receiver |
| Launcher `done` extra | PASS：只打开 App，无 `MARK_DONE` 日志 |
| 非导出 `NotificationActionActivity` 外部启动 | PASS：shell 启动被 `SecurityException` 拒绝 |
| 通知正文点击 | PASS：复制并打开小米笔记 |
| 已取件路径 | PASS：受控 `done` 将测试待办 `id=321` 置为 `is_finish=1` |
| 真实 `SmsProvider.insert` | PASS：root 插入测试短信 `_id=15191` → `77-7-7777` → 待办 `id=322` |
| 双通道去重 | PASS：同一短信重复 Hook 命中 `dedup skip`，未重复写入 |
| 测试数据清理 | PASS：待办 321/322、测试短信 15191 已删；用户原有短信保留 |

证据：

- `artifacts/stage0-home.png`（首页六项全绿）
- `artifacts/chain-written.png`（链路测试全通过）
- `artifacts/notification-expanded.png`（通知栏）
- `artifacts/stage0-device-info.txt`
- `artifacts/stage0-last-log.txt`

> 说明：通知动作按钮在当前 HyperOS 通知栏未直接展开，已用受控 `NotificationActionActivity` 的 `done` 路径验证同一段代码；后续 Stage 3 会在本地库接入后重新做完整 PendingIntent 点击测试。
> 隐私日志仍会打印短信正文片段（`SmsBridge` / `TodoWriter`），已登记到 Stage 4 收口，不在 Stage 0 改动范围。

## 四、Compose / Room 构建 spike

独立目录：`D:\project\WorkBuddyData\取件码助手\spike\compose-room`（不混入生产源码）。

| 项 | 结论 |
|---|---|
| Gradle | 8.6（现有 wrapper） |
| AGP | 8.4.2 |
| Kotlin | 1.9.24 |
| Compose BOM | 2024.06.00 |
| Room | 2.6.1（kapt） |
| compileSdk / targetSdk | 34 / 34 |
| 结果 | `assembleDebug` 成功 |
| Debug APK | 约 20 MB（Compose + Room，未开启 R8） |

结论：**当前 SDK 34 + Gradle 8.6 可以落地 Compose + Room**，不必先升 targetSdk；正式版需要 R8/keep 规则控制体积，并单独做 targetSdk 35/36 兼容性 spike。

## 五、未完成 / 风险

1. **真机安全验收已完成**：K90 Pro Max 上通过 `test/SecurityChecklist.md` 全部关键项；真实 `SmsProvider.insert` 链路、通知点击、受控已取件路径均验证。
2. **版本号降级**：设备原为 4.0.0(400)，本次已用 `adb install -r -d` 安装 3.6.0(360) 并保留数据；后续重装基线仍需允许降级，最终 4.0.0(400) 不受影响。
3. **中文路径**：AGP 8.2 对非 ASCII 路径有硬检查，已在 `gradle.properties` 加 `android.overridePathCheck=true`；CI 在 ASCII 路径不受影响。
4. **Provider UID 白名单**：HyperOS 4.0 上真实 `SmsProvider.insert` 已放行；其他 ROM 若返回 `rc=-3`，看 `IPC denied` 日志补充包名。
5. **通知动作**：正文点击与受控 `done` 已验证；HyperOS 通知栏动作按钮未直接展开，Stage 3 本地库接入后重做完整 PendingIntent 点击。
6. **Compose 体积**：debug 20MB；正式版需开 R8 并保留 Hook 反射类，目标 release ≤ 15MB。
7. **隐私日志**：`SmsBridge` / `TodoWriter` 仍会打印正文片段；Stage 4 收口，Stage 0 未改以免影响排查。
8. **targetSdk 未升级**：4.0 先保持 34；兼容性 spike 单独立项。

## 六、下一步

1. Stage 0 已通过；进入 Stage 1：`:core` / `:contract` 分层、规则单源、特征化测试。
2. 保留 `rollback-installed-4.0.0.apk` 作为回滚包；当前设备已运行 3.6.0-internal。
3. 所有改动仅在 `release/4.0` 本地；**未 push、未发 Release**。
