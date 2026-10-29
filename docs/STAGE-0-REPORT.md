# Stage 0 报告：基线冻结、版本收口、IPC 安全

> 日期：2026-10-30
> 分支：`release/4.0`
> 基线：v4.8 `fa244f3` → 内部版本 **3.6.0-internal / 360**
> 提交：`c8dc9bf`（安全/版本/基线）、`397917e`（Stage 0 文档）
> 状态：代码与构建完成；真机安全验收待设备连接

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

1. **真机安全验收未做**：手机当前未连接。`test/SecurityChecklist.md` 的 6 项需在 K90 Pro Max 上跑。
2. **版本号降级**：设备上现有 4.0.0(400)，基线是 3.6.0(360)。首次安装基线需 `adb install -r -d`（允许降级，保留数据）或卸载重装；最终 4.0.0(400) 不受影响。
3. **中文路径**：AGP 8.2 对非 ASCII 路径有硬检查，已在 `gradle.properties` 加 `android.overridePathCheck=true`；CI 在 ASCII 路径不受影响。
4. **Provider UID 白名单需真机确认**：不同 ROM 的短信/电话包名可能不同；若 Provider 返回 `rc=-3`，看 `IPC denied` 日志补充白名单。
5. **通知动作 Activity 需真机验证**：透明主题、剪贴板、root 标记、打开目标 App 的时序。
6. **Compose 体积**：debug 20MB；正式版需开 R8 并保留 Hook 反射类，目标 release ≤ 15MB。
7. **隐私日志**：`SmsBridge` / `TodoWriter` 仍会打印正文片段；Stage 4 收口，Stage 0 未改以免影响排查。
8. **targetSdk 未升级**：4.0 先保持 34；兼容性 spike 单独立项。

## 六、下一步

1. 连接 K90 Pro Max，按 `test/SecurityChecklist.md` 跑 6 项安全验收。
2. 用 `adb install -r -d` 安装 3.6.0-internal，确认短信/通知/写入/通知栏动作不回归。
3. Stage 0 通过后进入 Stage 1：`:core` / `:contract` 分层、规则单源、特征化测试。
4. 所有改动仅在 `release/4.0` 本地；**未 push、未发 Release**。
