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
6. **作用域现状（不是新方案）**：当前 scope 里没有 `com.miui.home`，所以探针现在没数据。
   但 11-12 已经验证过「有作用域也不注入」：scope 行在、`enabled=1`、重启桌面两次 + 重启手机一次，
   日志里仍然没有 `(com.miui.home)[...]`，探针文件始终没生成。
   **「勾上桌面作用域再试一次」是重复已失败的实验，不作推荐。** 详见 `WIDGET-RECT-SOURCES.md` 第 7 节。

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

### 6.1 前置：这条路已被证伪，不要重试

- 11-12 实测：作用域写入 `com.miui.home`（db `user_id=0`）、模块 `enabled=1`，
  重启桌面两次 + 重启手机一次，LSPosed 日志没有 `(com.miui.home)[...]`，探针文件没生成。
- 11-13 当前 scope 里那一行已经没了（大概率是 4.1.0 覆盖安装重置成推荐集合），
  但补回去也改变不了上面的结果。
- 代码（`LauncherProbe`）保留，等换 LSPosed 版本或 ROM 时再验证；现阶段用第 6.4 节的两条路。

### 6.2 Hook 点（仅作为将来恢复时的参考）

不要只 hook `onLayout`：MIUI 的子类若重写它又没调 super，基类 hook 收不到。
更稳的是从组件的创建点挂布局监听：

```
hook android.appwidget.AppWidgetHost#createView(Context, int, AppWidgetProviderInfo) after
    AppWidgetHostView v = (AppWidgetHostView) result;
    AppWidgetProviderInfo info = AppWidgetManager.getInstance(ctx).getAppWidgetInfo(id);
    if (!"io.github.okaidev.pickupcode".equals(info.provider.getPackageName())) return;
    v.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
        int[] p = new int[2];
        v.getLocationOnScreen(p);
        Rect r = new Rect(p[0], p[1], p[0] + v.getWidth(), p[1] + v.getHeight());
        if (!r.equals(last)) write(r);      // 变化超过 1px 才写
    });
```

`onLayout` 的 `l/t/r/b` 是相对父容器的，**不能直接当屏幕坐标**——4.1 的 `LauncherProbe`
这点做对了（用的是 `getLocationOnScreen`），但 hook 点选得浅。

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
