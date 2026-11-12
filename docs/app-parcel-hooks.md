# App 包裹数据采集（开发版）

目标是在 App 收到或加载包裹数据时提取，不依赖通知。此文记录首批适配和验证边界。

## 适配基线

从测试设备安装包静态核对：

| App | 包名 | 版本 | versionCode |
| --- | --- | --- | --- |
| 菜鸟 | `com.cainiao.wireless` | 8.11.1105 | 478 |
| 拼多多 | `com.xunmeng.pinduoduo` | 8.31.0 | 83100 |

APK、反编译文件和设备数据不提交到仓库。其他 App 或版本尚未验证。

## 数据入口

菜鸟首页入口：`PackageListJsManager.packageListDataChange(String)` 的 JS 数据回调，
发生在原生视图刷新之前。读取 `data.packageDataItemArray`，解开每项的 `packageData`
JSON 字符串，只接收 `bizzType/tag=PKG_INFO_ITEM`、`logisticStatus=AGENT_SIGN` 的包裹，
从 `title` 或 `richTitle.attributedNodes[].text` 中接收完整且符合格式的码。
这里是 App 传入的数据对象，不查询屏幕文字或 View。

地点来自同一连续驿站区块的 `STATION_INFO` 数据，校验 `groupId/anchorId` 一致。
多个驿站可共用一个分组 ID，不能用分组 ID 的字典让最后一个驿站覆盖前面的地点。
跨区块或缺少关联时地点记为未知；`validPackage` 不用于判断是否待取，真机仍显示
取件码的旧包裹也可能为 `false`。

菜鸟到站推送入口：`com.cainiao.wireless.packagelist.util.c.a(String, tb/tc/td)`，
安装包中的数据层将这三种推送转换为 `AGENT_SIGN` 包裹并保存。
Hook 只读取当前传入的一件包裹：`tb.ciR/ciS` 或 `tc/td.authCode/stationName`，
不遍历包含多个账号的本地缓存。这里涉及混淆类名，升级版本需重新核对。

菜鸟网络补充入口：`mtopsdk.mtop.domain.MtopResponse.parseJsonByte()` 完成后，检查
`api` 属于 `mtop.cainiao.*` 且 `retCode=SUCCESS`，从 `dataJsonObject` 获取已解析响应。
只识别 `PackageInfoDTO` 的结构：`logisticsStatus=AGENT_SIGN`、
`packageStation.authCode`、`packageStation.stationName`。
`showAuthCode=false`、非待取状态和掩码均不保存。不会把其他业务的 `authCode` 当成取件码。

菜鸟补充入口：`PickUpJsManager` / `NewPickUpJsManager.onPickUpPageDataChange(String)`
的数据回调，发生在原生渲染之前。只处理已核对的旧结构：`pageState=2` →
`spotsViewModels[]` → `packageItems[].title`，地点来自同一 spot 的 `title/address`。
`isSigning != 0` 跳过。新动态卡片的内部字段尚未适配；这类页面需上游响应包含上述
`PackageInfoDTO` 结构才会命中。

拼多多物流助手入口：在 `AMNetworkImpl.request(JSONObject, ICommonCallBack, ...)`
关联请求与回调，只接受 `/api/express_trackbox/list` 和
`/api/express_trackbox/list/cabinet/package` 两个路径。响应从
`AMNetworkImpl.callbackToJS(int, String, ICommonCallBack, boolean, Map)` 的原始字符串参数
读取，不读取 headers，不改变编码或回调；仅处理 HTTP 2xx 且业务 `success=true` 的响应。

- “我的快递”列表：`result.track_info_list[]` 中 `status_type=400` 为待取件，
  读取 `pick_code`。该字段实际可能是“取件码 1-2-3456”，只去除明确的“取件码”前缀及冒号，
  随后严格校验完整码。手机号文案、掩码、运输中和已签收条目均跳过。
  列表未提供驿站地址，因此地点记为未知，不从截断的物流文案猜地点。
- “取快递”汇总：`result.combine_list[].combine_track_list[].pick_code`，
  地址取所属分组的 `address`；此接口本身为当前待取列表，同样校验码。

拼多多订单物流补充入口：`CommonCallback.parseResponseStringWrapper(String)` 完成后，
接收 `com.xunmeng.pinduoduo.express.entry.CabinetInfo` 结果的 `result.code/address`，
仅接受 `status=3` 且 `showQrCodePickUp=false`。此入口尚未完成真实有件场景验收。
不处理二维码或物流轨迹里的自然语言取件码。

所有 Hook 均不更改原始返回值，也不读取 View、通知或网络输入流。
JSON 解析和 IPC 在独立、有界的单线程队列执行；响应上限 256 KiB、队列 16 项、
每次最多 100 个包裹。超限跳过并记录不含正文的诊断，不阻塞 App 的原回调。

## 入库与开关

新增 `TodoProvider` 的 `onAppParcel` 独立入口，只接收码和地点。
调用方 UID 必须实际属于声明的菜鸟/拼多多包名。两个 App 不能互相冒充，
不能使用短信入口或标记已取入口。
模块声明 `forceQueryable`，使目标 App 无需改自己的包可见性清单即可解析 Provider。
完成授权检查后清除调用方 Binder 身份，再执行模块自己的入库和提醒操作。

模块重新校验开关和字段后，复用 Room、系统待办和提醒链路。
结构化码和地点直接传入，不再套短信正则重新猜测。
沿用现有待取码去重及系统待办的“码 + 当天”去重，因此原有的跨驿站同码合并限制也仍存在。
成功的 IPC 在 Hook 进程内额外节流 30 秒，失败不会加入成功缓存；下次数据加载可以重试。

旧版数组与新版 `META-INF/xposed/scope.list` 均包含这两个可选目标。
默认关闭，在“设置 → App 内包裹提取”开启；简洁模式在“更多选项”中。
需要在 LSPosed 启用模块并勾选菜鸟、拼多多，随后重新启动对应 App。
开关配置在 Hook 侧最多缓存 5 秒，Provider 侧每次读取，关闭立即阻止新写入。

模块只观察 App 实际收到或加载到本地的数据，不主动请求平台接口。
如果 App 没运行、未登录，或者尚未收到含取件码的数据，不能保证后台发现新包裹。
拼多多页面可能沿用已有缓存；安装后可在“聊天 → 物流助手 → 查快递”切换到“待取件”，
触发 App 自己重新加载列表。模块不会自动刷新页面或发起额外请求。

## 本地验证

```bash
./gradlew :app:assembleDebug
ANDROID_JAR=/path/to/android.jar bash tools/test_app_parcel.sh
./gradlew :app:testDebugUnitTest --tests '*AppParcelProviderTest' --tests '*SwitchInteractionTest'
```

独立测试用合成数据覆盖多包裹地点对应、状态筛选、授权码混淆、掩码、超限和 UID 来源绑定。
Robolectric 测试验证真实 Provider 的开关和 IPC 拒绝路径。

真机验收需另做：确认 `APP-DATA hook ... count=...`，在登录后的 App 中刷新实际待取件，
确认 `APP-DATA delivered`、内部记录、小组件及所选系统待办，随后检查重复刷新不重复写入。
本地测试通过不等于上述真机链路已经验证。

### 当前设备进度（2026-11-12）

调试 APK 已通过 root 安装。已调用设备 LSPosed 2.1.0（7769）的管理接口启用模块，
并回读配置确认用户 0 的作用域为菜鸟、拼多多及模块自身；`app_parcel_enabled` 已开启。
解锁后，两个目标 App 的 Hook 注册均已确认，菜鸟首页回调已捕获两件真实待取包裹。
已核对本地库的两个码各自对应正确驿站，均为正式记录（`is_test=0`），重复回调未增加行数。
未向 App 注入合成包裹，也未用屏幕文字作为入库来源。

设备的取件码助手已在 ReSukiSU 中授权 root。手机未安装小米笔记，其 `todo.db` 不存在，
因此当前写入目标已设为内部记录；小米笔记后端未完成验收。
拼多多通过“聊天 → 物流助手 → 查快递 → 待取件”重新加载列表，
两件带完整码的真实包裹均已入库，来源为拼多多、`PENDING`、`is_test=0`。
第三件仅返回取件手机号，已跳过；列表无地址，两件地点均记为未知。
间隔超过 30 秒后重新加载列表，再次收到两次成功投递，记录仍为菜鸟 2 件、拼多多 2 件，未增加重复行。
“取快递”分组结构已核对并有合成测试；其完整入库链路及订单 `CabinetInfo` 后备入口
尚未完成真实有件场景验收。