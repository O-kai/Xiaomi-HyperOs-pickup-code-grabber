# 取件卡片与小米超级岛

## 桌面卡片

原生 RemoteViews 布局 `widget_pickup_v5`：大字号取件码、第二行地点、44dp 完成按钮，浅色与深色资源分别适配。卡片按高度显示完整数据行；长码按可用宽度缩小字号，不截断取件码。地点未知时显示来源。

点击码复制，点击完成按钮标记已取，点击卡片空白或标题查看全部。完成后行自然补位；不使用原生 RemoteViews 不支持的 alpha/translation 反射动画。关闭显示取件码后，整行隐藏，只保留件数汇总。

设置页使用相同的真实 RemoteViews 预览。预览数据仅在内存中生成，所有操作均禁用，不写入或修改本机包裹。

## 小米负一屏

`XiaomiPickupWidgetProvider` 单独声明小米 4×2 小部件，运行在 `:widgetProvider`，带有 `miuiWidget` 标识。应用声明 `miuiWidgetVersion`，支持小米刷新广播和 ID 恢复；普通桌面 Provider 保留。

“添加小组件 → 小米负一屏”按官方 `requestPinAppWidget` 扩展参数请求打开小米小部件详情页，由用户选择添加位置。**注册 Provider 不等于被小米小部件中心收录**：正式中心只展示审核通过的组件，未上架版本需要小米提供的测试环境。不会伪造上架状态，也不会修改负一屏数据库。

参考：[小米小部件技术规范](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1584)、[未发布组件与深色模式说明](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1591)。

## 小米超级岛

本地通知使用 `miui.focus.param` 的 `param_v2`，包含大岛、小岛、焦点卡片、图标与 PendingIntent 动作。支持 OS2 焦点通知与 OS3 超级岛协议。平台不允许展示时保留普通通知，不发送云推送、不上传包裹数据。

普通通知汇总当前待取包裹；完成或删除后仅更新仍然存在的提醒，全部完成时清除。用户划掉的通知不会因普通数据刷新重新出现。普通通知锁屏公开版与 AOD 文案仅包含件数，完整取件码位于私密通知内容。

**系统支持、焦点开关、平台鉴权是不同条件。** `canShowFocus=true` 不能证明小米平台鉴权成功。正式接入需要在小米开发者平台登记应用与签名、开通对应场景。构建时可传公开的应用 ID：

```sh
./gradlew :app:assembleRelease -PPICKUP_XIAOMI_APP_ID=你的应用ID
```

该参数进入 `com.xiaomi.xms.APP_ID`；调试版另带 `com.xiaomi.xms.BUILD_TYPE_DEBUG=true`，仍需平台配置对应调试签名/测试资格。不要填入 APP_SECRET、GitHub token 或其他密钥。

本次实机返回协议 3、焦点权限开启，系统日志显示模板渲染成功；最终鉴权返回 `-210`，错误为无法连接小米鉴权服务。因此尚不能宣称已验证实际上岛。负一屏应用列表同样出现网络不可用，未验证成功添加；桌面卡片已验证显示。

参考：[本地超级岛通知协议](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131)、[平台接入与签名鉴权](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2132)。

## 验证与示例图

`ParcelSurfacesTest` 使用真实 RemoteViews 验证布局、最小尺寸、大字、长码、深色模式重建、预览隔离，以及通知动作、锁屏/AOD 内容和普通通知回退。既有 App 数据采集与开关交互测试继续回归。

调试版独有、不可被其他应用启动的 `SurfacePreviewActivity` 可导出真实 Android 示例卡片。此 Activity 不进入发布版，示例图不含真实包裹或设备标识：

```sh
adb shell su -c 'am start -n io.github.okaidev.pickupcode/.SurfacePreviewActivity --ez export_preview true'
# 等待界面完成绘制后：
adb exec-out run-as io.github.okaidev.pickupcode cat files/widget-preview.png > widget-preview.png
# 深色示例跟随系统深色模式；切换后用 --activity-clear-top 重建 Activity。
```

旧的 `tools/gen_widget_preview.java` 仅用于历史 v4 预览；当前选择器图片从上述真实布局导出。

浅色与深色示例（由 Android 真实布局导出）：

![浅色取件卡片](screenshots/widget-card-light.png)

![深色取件卡片](screenshots/widget-card-dark.png)
