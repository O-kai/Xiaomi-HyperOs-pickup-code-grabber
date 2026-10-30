# 桌面小组件在 HyperOS 上的落地结论（2026-10-30 真机定位）

## 一、结论先说

小组件的**代码与系统注册都是正常的**，加不上是 HyperOS 桌面（MIUI Launcher）
的机制限制，不是本模块的实现问题。真机上逐项验证过：

| 检查项 | 结果 |
|---|---|
| 系统是否识别 provider | ✅ `dumpsys appwidget` 有 `[513] provider ... PickupWidgetProvider` |
| Provider 是否 exported | ✅ `android:exported="true"`（Android 12+ 要求） |
| 预览图 / 预览布局 | ✅ `previewImage` + `previewLayout` 都已提供 |
| 描述文字 | ✅ `android:description` 已提供 |
| 类别 | ✅ `widgetCategory="home_screen"` |
| 尺寸 | ⚠️ 曾被 `targetCellWidth/Height=4x2` 放大成 640dp×460dp（已修） |
| 桌面「添加小部件 → 全部应用」列表 | ❌ **列表里没有本 App** |
| `requestPinAppWidget` 直接固定 | ❌ 桌面 `AddItemActivity` 起来后 **同一毫秒内 on_destroy** |
| `secure/enabled_widgets` 白名单 | ✅ 已写入本包名（这是用户手动开「桌面小组件」时系统写的地方） |

## 二、定位到的三处原因

### 1. `targetCellWidth/targetCellHeight` 把建议尺寸撑爆

Android 12+ 会用 targetCell 的格数换算建议尺寸，`4x2` 在 HyperOS 上等于
**640dp × 460dp**——比手机屏宽（约 400dp）还大。

已去掉这两个属性，改由 `minWidth/minHeight`（250dp × 110dp）决定默认尺寸，
行数随实际分配高度自适应（`PickupWidgetProvider.onAppWidgetOptionsChanged`）。

### 2. 桌面的「全部应用」是系统内置应用清单

MIUI 的「添加小部件 → 全部应用」列出的
（笔记 / 超级小爱 / 抖音 / 翻译 / 高德地图 / 支付宝 / 携程 …）来自系统侧的
应用清单，**不在清单里的第三方 App 根本不会出现在这一页**。
本模块属于这一类，因此「长按桌面 → 小部件 → 全部应用 → 找到取件码助手」这条路走不通。

### 3. 直接固定被桌面立刻取消

`requestPinAppWidget` 会拉起 `com.miui.home.launcher.AddItemActivity`，
但它在同一毫秒内 `on_stop → on_destroy`，Activity 记录被直接移除。
日志证据见 `artifacts/` 下的抓取记录。

## 三、用户可执行的路径（已写进 App 内「添加到桌面」对话框）

1. 设置 → 应用设置 → 应用管理 → 取件码助手 → **桌面小组件** → 打开
   （这一步会把包名写进 `secure/enabled_widgets`）
2. 回到桌面，长按空白处 → 点「小部件」
3. 在列表里找到「取件码助手」，拖到桌面

## 四、开发期踩坑记录（重要）

**不要卸载重装本模块。** 2026-10-30 为刷新系统里缓存的小组件尺寸做过一次
卸载重装，代价是：

1. App 的 UID 变了（10053 → 10271）
   → Magisk 授权失效：su 对新 UID 返回 `not found`（exit 127）。
   修复：往 `/data/adb/magisk.db` 的 `policies` 插一条
   `insert into policies (uid, policy, until, logging, notification) values (<新uid>, 2, 0, 1, 1);`
2. App 数据目录属主/SELinux 上下文不再匹配
   → 本地库 `SQLiteCantOpenDatabaseException: Permission denied`。
   修复：删除 `shared_prefs`/`databases` 让 App 重建（由 App 自己建，权限与上下文才正确）。
3. **LSPosed 的作用域被清空**（`scope` 表里 `io.github.okaidev.pickupcode` 的行没了）
   → 短信 Hook 完全失效，日志里一条 `SMS:` 都没有。
   修复：手工往 `scope` 表补回
   `('io.github.okaidev.pickupcode','android',0)`、`com.android.phone`、`com.android.mms`、
   `com.android.providers.telephony`、`com.miui.notes`，
   并在 `modules_state` 里补 `enabled=1`；
   **然后必须再触发一次包替换**（`pm install -r`）让 LSPosed 重新读 DB，
   杀一次 `com.android.phone` 进程让 telephony 重新注入。

结论：正常迭代一律用 `pm install -r` 覆盖安装；需要清数据时用
「应用信息 → 存储 → 清除数据」，不要卸载。

## 五、已沉淀的工具

`tools/install_release.sh`：统一用 root 走 `pm install -r` 安装 release 包。
这台设备上 shell 身份的 `adb install` 会被判成
`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`（系统直接取消安装确认），
必须用 root 才装得上。
