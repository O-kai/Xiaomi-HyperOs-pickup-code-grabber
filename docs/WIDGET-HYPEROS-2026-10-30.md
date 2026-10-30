
## 补充二：`<View>` 不能出现在小组件布局里（2026-10-31 真机定位）

**现象**：桌面添加小组件时提示「载入窗口小部件时出现问题」，
**系统日志里什么错都没有**——桌面 inflate 失败，异常不会回传给 App。

**定位办法**：在 App 进程内用 `RemoteViews.apply()` 复现同一次 inflate。
一次就打出真相：

```
android.view.InflateException: Error inflating class android.view.View
Caused by: android.view.InflateException: Class not allowed to be inflated android.view.View
    at android.view.LayoutInflater.failNotAllowed(LayoutInflater.java:800)
```

**原因**：Android 的 RemoteViews 只允许 inflate 一份**白名单**里的具体 View 类
（`RemoteViews.ALLOWED_LAYOUT_CLASSES`）：
`FrameLayout` / `LinearLayout` / `RelativeLayout` / `GridLayout` / `ViewFlipper` /
`ScrollView` / `AbsListView` / `ListView` / `Spinner` / `TextView` / `ImageView` /
`ImageButton` / `ProgressBar` / `Button` / `CheckBox` / `RadioButton` /
`RadioGroup` / `EditText`。

**`android.view.View` 不在里面**（它是所有 View 的基类，语义上是抽象的），
**`android.widget.Space` 也不在里面**（纯占位控件）。

**踩坑经过**：4.0.2 为了让「查看全部」贴在组件底部，加了一个弹性占位：

```xml
<View android:layout_width="match_parent"
      android:layout_height="0dp" android:layout_weight="1" />
```

桌面 inflate 到这里直接失败 → 整个组件加不上。
**改成 `<TextView>`（空白、0dp 高、weight=1）即可**——TextView 在白名单里。

**教训**：
1. 小组件布局里**只能用白名单里的具体 View 类**。
   拿不准就用 `TextView` 顶替，它几乎能顶任何占位需求。
2. 桌面侧 inflate 失败**不会留任何日志**给 App。
   以后再遇到「加不上小组件」，先跑 App 内的**小组件自检**页
   （设置 → 桌面小组件 → 小组件自检），
   它会复现同一次 inflate 并把结论翻译成人话。

**顺带**：4.0.2 布局改结构后，桌面上已有的组件仍不会自动重建
（原因见上一节），所以修复后用户仍需移除重加一次。
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

## 补充：App 更新后桌面不重建小组件（2026-10-31 真机确认）

**现象**：App 升级后，桌面上已有的小组件仍是旧结构——
表现为「行距被压扁、标题不见、内容挤成一团」。

**原因**：Android 的 `AppWidgetHost` 在 App 更新后**不会重建**已有小组件的视图树。
它只在首次添加时 inflate 一次 `initialLayout`，之后的 `updateAppWidget` 只是往
那棵已存在的视图树上套新数据。改 `initialLayout` 的内容不会生效。

**试过并确认无效的做法**：

| 尝试 | 结果 |
|---|---|
| 改 `initialLayout` 指向的 XML 内容 | 无效，桌面仍是旧结构 |
| 改布局文件名（`widget_pickup` → `widget_pickup_v2`） | 无效 |
| 改根 View 的 `android:id` | 无效 |
| 重新 `updateAppWidget` | 无效（数据更新了，结构没变） |
| 广播 `APPWIDGET_OPTIONS_CHANGED` | 无效 |
| `requestPinAppWidget` | HyperOS 上返回成功但**什么都不做**（既有的 AddItemActivity 30ms 内自退） |

**唯一有效的办法**：移除旧的、重新添加一次。

**因此 4.0.2 做了两件事**：

1. 小组件设置页新增「**重新添加小组件**」按钮 + 明确提示，
   免得用户看到一个坏掉的组件却找不到原因；
2. 布局改动尽量收敛——结构稳定后，后续升级不再需要用户重新添加。

**顺带发现**：`bindAppWidgetIdIfAllowed`（公开 API，可在 App 内托管桌面上已有的组件）
在 HyperOS 上需要用户先在「应用设置 → 桌面小组件」给本 App 开开关，否则绑定被拒。
所以「App 内真实预览」这条路对多数用户不可用，没有做成正式功能。

**另一个 LinearLayout 的坑**：v4.0.1 把行高从 `layout_weight=1` 改成 `wrap_content` 后，
行被压扁了——LinearLayout 在空间不足且子视图无 weight 时会**均分压缩**子视图。
现在行高写死 `40dp`（固定值不会被压缩），放不下的行直接 GONE，剩余空间留白。
