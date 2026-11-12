# 取件卡片、小米超级岛与背屏

## 采集与刷新时机

菜鸟、拼多多采集是 **App 数据加载事件触发**：Hook 数据回调，在 App 自己收到或解析物流数据后提取取件码，通过本机 Provider 保存。模块不定时轮询快递接口，也不会为了获取包裹主动联网。App 没启动、没刷新或没收到新数据时，模块没有新的包裹来源；打开菜鸟首页或拼多多物流助手可触发 App 自己加载数据。App 的网络请求间隔由 App 控制，模块没有固定读取频率。

代码中的 5 秒是提取开关的本机缓存有效期，30 秒是同来源、同取件码、同地点的重复回调去重窗口，均不是抓取周期。另有用户可设置的每日取件提醒，仅提醒已保存的待取件，不查询新物流。

拼多多混合列表只接受 `status_type=400`（待取件）且 `pick_code` 包含有效明文取件码的行；物流助手待取列表只接受明确的 `pick_code`；柜机详情只接受可显示明文码的待取状态。缺失、空值、占位、掩码、手机号提示及非字符串值会跳过，不从手机号、运单号或物流描述猜码。已在途、已取件等状态不会录入混合列表。这里过滤的是**模块保存的记录**。

## 桌面卡片

原生 RemoteViews 布局 `widget_pickup_v5`：大字号取件码、第二行地点、44dp 完成按钮，浅色与深色资源分别适配。卡片按高度显示完整数据行；长码按可用宽度缩小字号，不截断取件码。地点未知时显示来源。

点击码复制，点击完成按钮标记已取，点击卡片空白或标题查看全部。完成后行自然补位；不使用原生 RemoteViews 不支持的 alpha/translation 反射动画。关闭显示取件码后，整行隐藏，只保留件数汇总。

设置页使用相同的真实 RemoteViews 预览。预览数据仅在内存中生成，所有操作均禁用，不写入或修改本机包裹。

## 小米负一屏

`XiaomiPickupWidgetProvider` 单独声明小米 4×2 小部件，运行在 `:widgetProvider`，带有 `miuiWidget` 标识。应用声明 `miuiWidgetVersion`，支持小米刷新广播和 ID 恢复；普通桌面 Provider 保留。

“添加小组件 → 小米负一屏”按官方 `requestPinAppWidget` 扩展参数请求打开小米小部件详情页，由用户选择添加位置。**注册 Provider 不等于被小米小部件中心收录**：正式中心只展示审核通过的组件，未上架版本需要小米提供的测试环境。不会伪造上架状态，也不会修改负一屏数据库。

参考：[小米小部件技术规范](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1584)、[未发布组件与深色模式说明](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1591)。

## 小米超级岛

本地通知使用 `miui.focus.param` 的 `param_v2`，包含大岛、小岛、焦点卡片、图标与 PendingIntent 动作。按系统报告的 `notification_focus_protocol >= 2` 判断能力，不把 ROM 名称或 OS 版本号当开关：支持 OS2 焦点通知、OS3 超级岛，也不会排除 OS4 或后续协议版本。**协议版本号不等于 HyperOS 版本号**。未报告能力或协议不可读取时使用普通通知；平台不允许展示时也保留普通通知，不发送云推送、不上传包裹数据。

普通通知汇总当前待取包裹；完成或删除后仅更新仍然存在的提醒，全部完成时清除。用户划掉的通知不会因普通数据刷新重新出现。普通通知锁屏公开版与 AOD 文案仅包含件数，完整取件码位于私密通知内容。

**系统支持、焦点开关、平台鉴权是不同条件。** `canShowFocus=true` 不能证明小米平台鉴权成功。正式接入需要在小米开发者平台登记应用与签名、开通对应场景。构建时可传公开的应用 ID：

```sh
./gradlew :app:assembleRelease -PPICKUP_XIAOMI_APP_ID=你的应用ID
```

该参数进入 `com.xiaomi.xms.APP_ID`；调试版另带 `com.xiaomi.xms.BUILD_TYPE_DEBUG=true`，仍需平台配置对应调试签名/测试资格。不要填入 APP_SECRET、GitHub token 或其他密钥。

小米 17 Pro Max 的 HyperOS 4 / Android 17 实机报告协议 3。在已有 HyperCeiler 解锁配置的环境中，示例通知鉴权返回 0，SystemUI 进入大岛展开状态，用户已确认正面实际可见。此结果不代表未解锁设备已获得小米正式接入资格。此前另一台 OS3 设备返回 `-210`、无法连接鉴权服务，是该设备当时的连接失败；与这次 OS4 的成功结果分别记录。负一屏仍未验证成功添加，桌面卡片已验证显示。

参考：[本地超级岛通知协议](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131)、[平台接入与签名鉴权](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2132)、[HyperOS 4 官网](https://os.mi.com/)。

### 已使用 LSPosed / HyperCeiler 的设备

模块设置或简洁模式「更多选项」中的「超级岛与背屏」提供通知设置入口与以下说明。系统通知权限仍需开启，HyperCeiler 的两种解锁处理的是不同条件：

| HyperCeiler 入口 | 功能与作用域 |
| --- | --- |
| 系统界面 → 控制中心 → 移除焦点通知白名单；配置焦点通知列表 → 选择「取件码助手」 | SystemUI 展示白名单；作用于 `com.android.systemui` |
| 小米服务框架 → 解锁焦点通知白名单签名验证 | 小米鉴权结果；作用于 `com.xiaomi.xmsf` |

按 HyperCeiler 提示重启对应作用域。只放开 SystemUI 白名单不能证明鉴权已放行；只放开鉴权也不代表系统通知开关、背屏模板等条件已满足。上述入口与实现已对照源码，不同 HyperCeiler / ROM 版本可能有变化，OS4 已在上述实机环境验证上岛，其他 ROM / 模块组合仍需验证；本项目不自动安装、修改或启用其他模块。

源码依据（HyperCeiler `0830c5875570e66ae576b9276b0e57033cb6601a`）：[SystemUI 白名单 Hook](https://github.com/ReChronoRain/HyperCeiler/blob/0830c5875570e66ae576b9276b0e57033cb6601a/library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/rules/systemui/statusbar/island/UnlockFocus.kt)、[小米服务框架鉴权 Hook](https://github.com/ReChronoRain/HyperCeiler/blob/0830c5875570e66ae576b9276b0e57033cb6601a/library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/rules/xmsf/UnlockFoucsAuth.kt)。未复制这些 Hook 的代码。

## 小米 17 Pro / Pro Max 背屏

标准超级岛通知仍作为正面入口；检测到 `com.xiaomi.subscreencenter` 后，同一条通知另携带独立的背屏 RemoteViews：`miui.rear.param` / `rear_param_v1` 声明快递服务，`miui.rear.rv` 提供大字取件码、地点、件数及“标记已取 / 查看全部”按钮。直接提供布局，避免依赖系统内置的第三方 App 模板。完成或删除后随原通知更新，全部完成时清除；静默更新不申请再次浮出。背屏通知与桌面 / 负一屏的 AppWidget 是不同展示机制。

这是对实际 OS4 背屏宿主行为的适配，包含非公开接口，后续系统版本可能变化。小米官方说明标准超级岛可由小米侧自动适配，但本地未登记的应用仍可能受展示开关与鉴权限制，不能仅凭该说明保证显示。未安装背屏宿主的设备不附加这些布局。

**背屏的应用开关与正面的超级岛开关独立。** 设置或简洁模式“更多选项 → 超级岛与背屏 → 启用背屏”可通过 root 打开本应用当前用户的开关。此操作仅由用户点击触发，通过系统 `SubScreenAppProvider` 查询、插入缺失的本应用条目并更新开关，最后读回确认；不修改其他应用、不删除条目、不直接改写数据库。首次开启后重启手机加载配置。仍须允许系统通知并满足系统焦点权限条件。

`miui.rear.rvAOD` 始终提供单独的黑底件数布局，不含取件码、地点或按钮。实机宿主在该字段缺失时会回退到完整卡片，故不能只设置正面通知的 `aodTitle`。息屏验证已确认切换为件数页面。**这是息屏隐藏，不是背屏唤醒后的身份认证**：点亮背屏可显示完整码，文案不声称必须解锁手机才能查看。普通通知的锁屏公开版同样只显示件数。

17 Pro Max 实机的宿主分配区域包含左侧摄像头遮挡区；普通卡片和息屏卡片都避让该区域。优先读取可见副屏的 cutout；私有副屏不可枚举时，针对实测设备 `popsicle` 使用 106dp 左侧安全区，单位在宿主屏幕密度下解析。其他机型及未知遮挡形状尚需实机验证。

目前 OS4 / Android 17 实机已接受独立卡片，背屏截图可见取件码、件数、地点和按钮；息屏截图只含件数提示。示例动作仅查看预览或清除示例通知，不修改真实包裹。生产“已取件”使用与普通通知共用的不可变广播 PendingIntent，接收器不导出；直接在后台完成记录，不启动页面或要求解锁。记录完成时更新同一条通知。

依据：[小米背屏自动适配说明](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2144)。独立布局和开关行为另外对照实机系统背屏宿主验证；未复制 OEM、HyperCeiler 或 REAREye 源码，设备 APK、日志、屏幕截图均不加入仓库。

## 验证与示例图

`ParcelSurfacesTest` 使用真实 RemoteViews 验证布局、最小尺寸、大字、长码、深色模式重建、预览隔离，以及通知动作、锁屏/AOD 内容、协议 2–5 的标准模板和普通通知回退。另验证背屏布局、长码、摄像头避让、独立息屏内容和静默更新。模拟测试与上文实机显示结果分别记录。`AppParcelDataTest` 验证拼多多各入口仅录入明确的有效取件码，包含手机号、空码、非字符串和描述干扰场景。既有 App 数据采集与开关交互测试继续回归。

本轮 Android 回归共 45 项通过，debug、独立探针和 release 构建均通过；实机另验证了示例按钮直接清除通知及背屏卡片。

`AppParcelFlowTest` 用合成拼多多响应串联真实解析器、调用方校验、Provider、Room 数据库、通知与 RemoteViews：混合列表仅保存两个有效待取码，连续加载三次仍为两条；发送真实通知的“已取件”广播 PendingIntent 后，在不启动 Activity 的情况下完成一个，通知静默切换到另一个，相同响应重载不会恢复已完成记录，全部完成后通知清除。这是 Android 模拟环境集成验证，不代表 LSPosed 注入或小米系统展示已经实测通过。

调试版独有、不可被其他应用启动的 `SurfacePreviewActivity` 可导出真实 Android 示例卡片。此 Activity 不进入发布版，示例图不含真实包裹或设备标识：

```sh
adb shell "su -c 'am start -n io.github.okaidev.pickupcode/.SurfacePreviewActivity --ez export_preview true'"
# 等待界面完成绘制后：
adb exec-out run-as io.github.okaidev.pickupcode cat files/widget-preview.png > widget-preview.png
# 深色示例跟随系统深色模式；切换后用 --activity-clear-top 重建 Activity。
# 独立示例通知，不写入数据库，动作只打开预览或结束验证：
adb shell "su -c 'am start --activity-clear-top -n io.github.okaidev.pickupcode/.SurfacePreviewActivity --ez show_island_sample true'"
# 验证后清除示例通知（独立 ID 1009）：
adb shell "su -c 'am start --activity-clear-top -n io.github.okaidev.pickupcode/.SurfacePreviewActivity --ez dismiss_island_sample true'"
```

当设备上原应用使用另一把签名密钥时，可构建独立渲染探针：

```sh
./gradlew :app:assembleSurfaceProbe
adb install -r app/build/outputs/apk/surfaceProbe/app-surfaceProbe.apk
adb shell am start --activity-clear-top -n io.github.okaidev.pickupcode.surfaceprobe/io.github.okaidev.pickupcode.SurfacePreviewActivity --ez show_island_sample true
# 结束后，将最后一个参数改为 --ez dismiss_island_sample true。
```

探针使用 `.surfaceprobe` 独立包名和 Provider authority，禁用采集 Provider 与模块声明，移除现代 Xposed 入口元数据；仅用于显示合成示例，不替换已安装的正式应用。本次 17 Pro Max 验证采用该探针，原应用及数据保留。正面实际上岛、背屏渲染和息屏切换已验证；这不等于新版采集 Hook 已替换手机中原模块。正常升级仍须使用与原应用匹配的签名。

此前拼多多进程的三个 Hook 点均已加载；这只证明当时注入成功，不等于本次设备上新增包裹数据采集成功。

旧的 `tools/gen_widget_preview.java` 仅用于历史 v4 预览；当前选择器图片从上述真实布局导出。

浅色与深色示例（由 Android 真实布局导出）：

![浅色取件卡片](screenshots/widget-card-light.png)

![深色取件卡片](screenshots/widget-card-dark.png)
