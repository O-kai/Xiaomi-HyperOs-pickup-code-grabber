# K90 桌面小组件：像素矩形实测 + 方案复核（2026-11-13）

> 对象：Redmi K90 Pro Max（`d95a11de`，25102RKBEC，HyperOS，Android 17 / API 102，1200×2608 @480dpi）。
> 目的：核对另一份「uiautomator + launcher.db + onLayout hook」的方案，并给出可落地的数据。
> 所有 adb 命令都带 `-s d95a11de`；13 Ultra（`bb35aa85`）全程未操作。

## 0. 结论

1. **组件确实存在，占 5×3 格**：`screen=3, cellX=0, cellY=4, spanX=5, spanY=3, appWidgetId=4104`。
2. **实测像素矩形 = (78, 1056) – (1121, 1686)**，即 **1044 × 631 px**（348 × 210 dp）。
3. **`uiautomator dump` 在这台机器上不可用**：三次尝试全部 `Killed`（exit 137），root 下也一样。
4. **`dumpsys activity top` 拿不到桌面的 View 树**：MIUI 的 `Launcher.dump()` 只打自己的
   `dump favorites`，没有 `View Hierarchy:`；但**它顺带打出了整份 favorites**，格子数据不用 root 也能拿。
5. **数据库路径不是 `/data/data/com.miui.home/databases/launcher.db`**，
   实际是 `/data/user_de/0/com.miui.home/databases/launcher5x9.db`。
6. **最关键的修正**：本模块的 LSPosed 作用域里**没有 `com.miui.home`**
   （只有 phone / mms / system / telephony），所以 `LauncherProbe` 从来没被加载过。
   「USAP 挡住注入」的旧结论不成立——usap64 池进程本身在 LSPosed 日志里就有注入记录。

## 1. 设备与进程

```
$ adb devices -l
bb35aa85  device  2304FPN6DC（13 Ultra，未操作）
d95a11de  device  25102RKBEC（K90 Pro Max）

$ adb -s d95a11de shell "ps -A -o PID,PPID,NAME | grep -E 'com.miui.home|usap'"
4673      1     usap64
22310  2200     usap64
26075  2200     usap64
27457  4673     com.miui.home
```

LSPosed：

```
$ lspctl status
LSPosed 2.2.1 (7912) / API 102 / System server: connected
Modules: 21 installed, 16 enabled user states
```

作用域（`/data/adb/lspd/config/modules_config.db` → `scope`）：

```
io.github.okaidev.pickupcode  com.android.phone
io.github.okaidev.pickupcode  com.android.mms
io.github.okaidev.pickupcode  system
io.github.okaidev.pickupcode  com.android.providers.telephony
```

→ 没有 `com.miui.home`，`LauncherProbe` 不会安装；探针文件也从未生成：

```
$ ls /data/user/0/com.miui.home/files/pickup_widget_rect.txt
No such file or directory
```

## 2. 像素矩形：uiautomator 失败，改用截图测量

### 2.1 uiautomator（原方案第 2 步）

```
$ adb -s d95a11de shell "uiautomator dump /sdcard/dw.xml; echo exit=$?"
Killed
exit=137
```

`--compressed`、`su -c` 三种方式结果相同，文件都没有生成。**这条路在本机不可用**，
不是权限问题（root 下也被杀）。

### 2.2 截图测量

```
$ adb -s d95a11de exec-out screencap -p > desk.png      # 1200×2608
```

卡片背景是 `#E61B1B1B`（90% 不透明）+ 20dp 圆角，在壁纸上形成稳定暗区。
用亮度阈值 + 多行采样找四条边：

| 边 | 采样结果 |
|---|---|
| 上 | `y=1056`（外侧 210 → 内侧 44） |
| 下 | `y=1686`（内侧 43 → 外侧 204） |
| 左 | `x=78`（y=1300/1350/1400/1500 一致） |
| 右 | `x=1120~1121`（y=1350~1600 一致） |

**最终矩形：left=78, top=1056, right=1121, bottom=1686 → 1044×631 px。**

### 2.3 与格子数据互算

span=5×3，所以：

- 格子宽 = 1044 ÷ 5 = **208.8 px（69.6 dp）**
- 格子高 = 631 ÷ 3 = **210.3 px（70.1 dp）**
- 网格原点：x=78（26 dp）、y=1056 − 4×210.3 = **214.8 px（71.6 dp）**

左右留白对称（78 / 79），说明 5 列等分的是「屏宽减两侧 26dp」。

## 3. 格子数据：两条来源，都不需要 root

### 3.1 `dumpsys activity top`（免 root）

桌面 Activity 段落里直接带整份 favorites（173 条），我们的条目：

```
[ id=300 itemType=4 cellX=0 cellY=4 spanX=5 spanY=3 screenId=3
  appWidgetId=4104 label=Some("取件码助手")
  appWidgetProvide=Some("io.github.okaidev.pickupcode/io.github.okaidev.pickupcode.PickupWidgetProvider") ]
```

### 3.2 数据库（root）

```
$ ls /data/user_de/0/com.miui.home/databases/
launcher5x9.db   miui_widget.db   onetrack_cache.db   app_category_new.db …
```

注意：`/data/data/com.miui.home/databases/` 里只有 `onetrack_cache.db`，**没有 launcher.db**。

```
select _id,itemType,screen,cellX,cellY,spanX,spanY,appWidgetId,appWidgetProvider
from favorites where itemType=4;

(105, 4, 1, 0, 5, 4, 2, 8, 'com.android.calendar/...NearEventAppWidget')
(300, 4, 3, 0, 4, 5, 3, 4104, 'io.github.okaidev.pickupcode/...PickupWidgetProvider')
```

列名是 `screen`，不是 `screenId`；`itemType=4` 的判断是对的。

## 4. 现有 4.1 估算 vs 实测

代码（`WidgetPlacement.toRatios`）用 `cell = 屏宽 ÷ 列数`、`wsTop = 屏高 × 2%`：

| | 估算 | 实测 | 偏差 |
|---|---|---|---|
| left | 0 | 78 | +78 |
| top | 1012 | 1056 | +44 |
| right | 1200 | 1121 | −79 |
| bottom | 1732 | 1686 | −46 |
| 宽 × 高 | 1200 × 720 | 1044 × 631 | −156 × −89 |

原因是 MIUI 的 workspace 两侧各有 26dp 留白，格子不是 `屏宽÷列数`。
**预览背景会整体偏左、偏上，并放大约 15%**——这是「看起来还行但和桌面不完全重合」的根因。

## 5. 对原方案的逐条评审

| 原方案 | 结论 |
|---|---|
| 1 设备识别、必须 `-s` | ✅ 正确，已执行 |
| 2.1 `uiautomator dump` 取 bounds | ❌ 本机不可用（三次全 Killed/137） |
| 2.2 `dumpsys activity top` 交叉验证 View 树 | ❌ MIUI 的 `Launcher.dump()` 不打 View Hierarchy；但**能打 favorites** |
| 3 `/data/data/com.miui.home/databases/launcher.db`，itemType=4 | ⚠️ 路径错、表名对；实际是 `/data/user_de/0/.../launcher5x9.db`，列名 `screen` |
| 3 与像素尺寸对应 | ✅ 能做到（本文第 2、3 节） |
| 4 Hook `AppWidgetHostView.onLayout` | ⚠️ 方向对，但**前提没满足**：模块作用域不含桌面，hook 从没装进去过 |
| 4 按包名过滤、数据透出 | 需要补实现细节，见第 6 节 |
| 5 输出命令与结果 | ✅ 本文即交付 |

## 6. 修正后的实现方案

### 6.1 前置：把桌面加进作用域（必须先做）

- 目标包名：`com.miui.home`（用 `PackageManager` 查默认桌面，别写死）。
- 当前作用域只有 4 项，等于 `scope.list` 的推荐集合——**覆盖安装后大概率被重置**，
  这解释了「以前勾过、后来没数据」。
- 加完必须重启桌面进程（`am force-stop com.miui.home` 后回桌面）或重启手机。

### 6.2 Hook 点

```
进程：com.miui.home
类：  android.appwidget.AppWidgetHostView（及其 MIUI 子类，findClassIfExists）
方法：onLayout(boolean changed, int l, int t, int r, int b)
时机：afterHookedMethod
读取：getLocationOnScreen(int[2]) + getWidth()/getHeight()
过滤：AppWidgetManager.getAppWidgetInfo(id).provider.getPackageName().equals(本包)
节流：矩形变化超过 1px 才写；每次 layout 都写会拖慢桌面
```

`onLayout` 的 `l/t/r/b` 是相对父容器的，**不能直接当屏幕坐标**；
必须 `getLocationOnScreen`。这也是 4.1 里 `LauncherProbe` 已经做对的部分。

### 6.3 透出方式

现有做法（写 `/data/user/0/com.miui.home/files/pickup_widget_rect.txt`，App 用 root 读）
可用，但依赖 root。两个补充：

- Hook 里直接 `sendBroadcast` 到本 App（`setPackage`），显式接收器 + 自定义权限；
  免 root、实时；
- 尺寸变化另有官方回调：`AppWidgetProvider.onAppWidgetOptionsChanged` 会带
  `OPTION_APPWIDGET_MIN_WIDTH/HEIGHT`（dp），不需要 hook，作为兜底与交叉校验。

### 6.4 没有 root / 不想勾作用域时

- 无障碍：桌面窗口 `getWindows()` → 找 `content-desc == 取件码助手` 的节点 →
  `getBoundsInScreen()`。历史抓取（`artifacts/wu.xml`）已证明 MIUI 会暴露组件子树与坐标。
- 截图测量：本文第 2 节的方法，适合「一次性校准」，不适合每次运行。

## 7. 最终数据（可直接写进代码/文档）

```
包名      io.github.okaidev.pickupcode
组件      io.github.okaidev.pickupcode.PickupWidgetProvider
appWidgetId 4104
屏幕      1200 × 2608 px（400 × 869 dp，density 3.0）
格子      screen=3, cellX=0, cellY=4, spanX=5, spanY=3
像素矩形  left=78, top=1056, right=1121, bottom=1686
尺寸      1044 × 631 px（348 × 210 dp）
网格      5 列 × 9 行；格子 208.8 × 210.3 px（69.6 × 70.1 dp）
原点      x=78（26 dp）, y=214.8（71.6 dp）
```
