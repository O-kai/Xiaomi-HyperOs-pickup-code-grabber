# API 102 迁移（4.2.0）

4.2.0 是纯现代 Xposed 模块。需要实现 libxposed API 102 的框架；不再支持仅提供 legacy API 82 或现代 API 101 的框架。

## 入口与调用链

- 编译依赖 `io.github.libxposed:api:102.0.0`（compileOnly），应用侧使用 Maven Central 已发布的 `service:102.0.0`。主分支 README 提及的 102.0.1 尚未在 Maven Central 发布，因此不使用。
- `META-INF/xposed/java_init.list` 声明 `XposedEntry`，`module.prop` 的最低与目标 API 均为 102。移除 `assets/xposed_init`、旧 Manifest 元数据和旧推荐作用域数组。
- 通过 `onPackageReady` 获取 AppComponentFactory 最终提供的 ClassLoader；按包名和 ClassLoader 防止重复挂载。系统框架使用独立的 `onSystemServerStarting` 回调，作用域为 `system`，不能用普通包 `android` 替代。
- Hook 使用 `hook(...).intercept(...)`。观察前后事件时原方法只执行一次；原方法异常原样传递，提取异常隔离。ColorOS 放行只短路本模块 Provider 的 system/phone 调用，其他调用继续原链。
- `Chain` 仅用于当前同步调用。后台任务只接收字符串和必要字段快照。通过 `Application.attach` 捕获 App Context，替代 AndroidAppHelper。
- 拼多多回调通过有容量上限的弱引用身份表关联物流接口，复用回调请求时先清除旧关联。不同但 equals 相等的回调不会串数据。

## 激活、配置与数据

模块自身不再被 Hook。应用通过官方 `XposedServiceHelper` 接收框架服务，服务连接作为当前激活证据；旧的 `pickup_injected.flag` 不参与判断。目标加载日志与成功入库是另外两项证据，不用激活状态冒充采集成功。

通知开关、包裹提取开关、冻结直写开关、官方/用户规则、黑名单、模板和目标选择组成一个快照，通过框架远程偏好同步。设置仍先保存到应用私有偏好，服务连接后重新推送；断连时保留本地配置。远程偏好由框架持久化，目标侧只读，不再通过公开目录文件、su、SELinux 标签或 LSPosed 私有配置数据库传递配置。用户规则中的短信测试样本不进入快照。

菜鸟/拼多多仍在实际数据加载回调触发，无定时网络轮询。关闭开关后 Provider 每次鉴权时检查本地值，即使目标进程短暂持有旧快照也不能新增入库。业务数据继续由原有 UID 校验 Provider 送到 Room，保留去重、拼多多仅明确取件码的筛选、系统待办、桌面卡片、超级岛和背屏。

框架服务可能在首次解锁前启动应用。Application 只注册服务监听；首次解锁前不读取凭据加密的偏好和 Room。

## 构建与升级

使用 JDK 17、AGP 9.1.1、Gradle 9.3.1、Android SDK Platform 37.0 / Build Tools 37.0.0。compileSdk 升到 37 是服务库的要求；minSdk 28、targetSdk 34 保持原值。Robolectric 升到 4.17，与 Android 37 编译类路径兼容。

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleSurfaceProbe :app:assembleRelease
ANDROID_HOME=/path/to/android-sdk bash tools/test_app_parcel.sh
sh tools/test_coloros_gate.sh
```

使用同一签名覆盖安装即可保留数据。安装后确认 LSPosed 按现代 API 102 识别模块，保留原作用域并重启菜鸟、拼多多及相关短信进程。系统框架 Hook 需要重启手机后加载新代码；框架服务已连接不能说明 system_server 已更新。自动热重载关闭，避免长驻线程/旧 Hook 未清理造成重复采集。

官方资料：[模块 API](https://github.com/libxposed/api)、[框架服务](https://github.com/libxposed/service)、[现代模块示例](https://github.com/libxposed/example)、[AGP 9.1.1](https://developer.android.com/build/releases/agp-9-1-0-release-notes)。

## 验证记录

2026-11-13：54 项 Android/Robolectric 测试通过（含 Hook 结果/异常语义、ColorOS 放行边界、框架配置同步和原有卡片/Provider 链路），包裹解析 117 项、ColorOS 类检测 33 项通过；debug、surfaceProbe 和 release 构建及 release 必需 lint 检查通过。APK 检查确认现代入口存在、旧入口及 legacy API 引用消失，框架 API 类未被打包，渲染探针未注册成模块。

在小米 17 Pro Max / HyperOS 4 / Android 17 上，同签名覆盖安装成功，应用数据保留。LSPosed 识别为 modern、min/target API 102，原有六项作用域保留。普通应用界面已显示框架服务连接；菜鸟五类数据 Hook 和拼多多三类 Hook 均有实际挂载日志，菜鸟首页真实数据触发候选提取及本地入库。手机重启后的日志已确认 API 102 的 `onSystemServerStarting`、通知 Hook 和电话/短信 Provider Hook 挂载。

本轮真机验收：拼多多物流助手列表有三件待取包裹，其中两件提供完整取件码、另一件仅有取件手机号。实际回调成功交付两次，本地库中拼多多正式待取记录为两条（`PENDING`、`is_test=0`）；切换列表后未新增重复记录。正面超级岛已显示真实取件码。桌面标准小组件和小米小组件 Provider 均已注册；本轮未验收桌面或负一屏的实际添加与交互。

背屏未通过本轮验收：重新安装后，系统背屏应用表缺少本模块。曾恢复且回读确认用户 0 本模块的 `enable=1`，但重启背屏宿主后再次查询，该记录已不存在。将现有真实通知短暂延后后立即恢复，重新触发接收；通知中包含 `miui.rear.param`、普通/AOD RemoteViews，背屏宿主也记录了本模块通知的 `handleNotificationPosted`，但用户确认背面仍没有取件卡片。反编译同版本宿主确认：通知处理先检查支持列表及应用开关，不在支持列表时直接返回，尚未进入 RemoteViews 渲染。这与现场现象一致，但记录被移除的具体触发路径仍需进一步定位。不能以协议字段存在或宿主收到通知替代实际显示通过。

另有尚未修复的问题：常驻 root 通道在 su 已退出时仍等待每路径 20 秒；完整体检保留了 root 失败时误报配置快照失败的旧分支；体检并发执行存在结果覆盖风险。上述问题不在本轮验收中修改。
