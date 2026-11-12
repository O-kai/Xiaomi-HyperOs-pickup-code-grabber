# 小组件真实矩形：可取的数据源与代价

> 2026-11-13 起。4.1.0 的 `LauncherProbe` 依赖 LSPosed 注入桌面进程，在 HyperOS 上被
> USAP 池挡住。这份文档把「还能从哪儿拿到那个矩形」逐条摆开，供 4.2 选型。
> 所有结论都标注了来源：AOSP 代码 / 真机抓取 / 待验证。

## 0. 结论先说

- **不要关 USAP 池**。那是把整机的进程出生方式改回 fork-on-demand，只为一个预览矩形。
- **首选：无障碍读桌面窗口的节点树**。K90 的历史抓取已经证明能读到组件根节点和每一行的
  屏幕坐标，精度是像素级，且不依赖注入、不依赖 USAP。
- **次选：root 侧 `dumpsys activity top` 抓 View 树**。不开无障碍也能拿到，代价是要在
  「桌面在前台」的那一刻执行。
- **兜底：现有格子估算**，并按「精确 / 估算」在界面上标注来源。

## 1. 为什么这件事本来就难

Android 没有「查询某个小组件在桌面上的位置」的公开 API：

- `AppWidgetManager.getAppWidgetOptions()` 只给尺寸（dp），不给坐标；
- 桌面的 `favorites` 库只有格子坐标（cellX/cellY/spanX/spanY），没有像素；
- `AppWidgetHostView` 的 `getLocationOnScreen()` 只在桌面进程里有意义。

所以只有四条门：

| 门 | 做法 | 前提 |
|---|---|---|
| 进那个进程 | LSPosed hook 桌面，直接读 View | 桌面进程被注入 |
| 让系统 dump | `dumpsys activity top` 打 View 树 | root + 桌面在前台 |
| 读无障碍树 | `AccessibilityService.getWindows()` | 用户授权 |
| 反推 | 尺寸 + 格子 + 留白常量 | 无 |

4.1 走的是第一条门，卡在 USAP 上。

## 2. USAP 是什么，为什么它挡住注入

AOSP 的定义（`ZygoteConfig.java`）：USAP = unspecialized app process。zygote 在空闲时
**预先 fork** 一批「还没有身份」的进程放进池子；启动 App 时不再 fork，而是从池子里取一个，
把 UID / cgroup / SELinux 上下文「特化」进去。省掉的是 fork 的十几到几十毫秒。

```
普通路径：  zygote ──fork──> app 进程 ──> handleBindApplication ──> LSPosed 注入
USAP 路径： zygote ──pre-fork──> usap64 池 ──specialize──> 桌面进程 ──> 注入时机对不上
```

K90 上的实测（2026-11-11）：

- 桌面 `com.miui.home` 的父进程是 `usap64`，不是 `zygote`；
- LSPosed 日志里本次开机注入的只有 `system` / `com.android.phone` / 本 App，**没有桌面**；
- `device_config get runtime_native usap_pool_enabled` → `null`（没有覆盖值）。
  AOSP 的默认值是 **false**（`ZygoteConfig.USAP_POOL_ENABLED_DEFAULT`），
  但桌面父进程确实是 `usap64`，说明小米在 `dalvik.vm.usap_pool_enabled` 里把它打开了。

> **2026-11-13 更正（重要）**：上面三条只能证明「桌面进程里没有模块」，
> 不能证明是 USAP 造成的。当天复查 `/data/adb/lspd/config/modules_config.db` 的 `scope` 表：
> 本模块只勾了 `com.android.phone` / `com.android.mms` / `system` /
> `com.android.providers.telephony`，**没有 `com.miui.home`**。
> 同时 `usap64`（pid 4673）自己在 LSPosed 日志里有 `HyperOS Runtime injected`——
> 池进程是带框架的。**直接原因是作用域没勾，USAP 阻断不成立。**
> 第 7 节的「重启桌面」方案作废，见更正后的第 7 节。

开关：`device_config put runtime_native usap_pool_enabled false`（或 `setprop dalvik.vm.usap_pool_enabled false`）。
读发生在 zygote 启动时，**要重启才生效**；改的是全局，所有 App 的启动路径一起变。

## 3. 路线 A：无障碍读桌面窗口（首选）

### 能读到什么（已实证）

`artifacts/wu.xml`（2026-10-31，K90，桌面拖拽态，uiautomator dump）：

```
View  [37,832][1162,1732]  content-desc='取件码助手'      ← AppWidgetHostView，就是卡片矩形
  View [78,832][1122,1688]
    View [106,860][1094,926]
      Button [106,938][381,1007] text='71-2-3344'          ← 每一行的码
      Button [404,950][1014,994] text='江南绿城西门'        ← 地点
```

`artifacts/launcher.xml`（2026-10-30，正常桌面，非拖拽态）：

```
View [37,1282][938,2182] content-desc='笔记'   ← 系统笔记组件的 host 节点，同样带坐标
```

两条结论：

1. 桌面把小组件当成普通 View 子树暴露给无障碍，**host 节点的 content-desc 就是组件标签**；
2. 正常桌面态也能拿到（不只是拖拽/调整大小态）。

### 怎么用

- 服务声明 `canRetrieveWindowContent` + `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`；
- `getWindows()` 只返回「用户当前能交互的窗口」——我们的页面盖住桌面时读不到桌面，
  所以要在**用户回到桌面**时读：监听 `TYPE_WINDOW_STATE_CHANGED`，包名等于桌面，
  延迟 300~500ms 取一次，缓存矩形；
- 组件根节点 = 桌面窗口里 `content-desc == 应用名` 且面积明显大于图标的节点
  （图标约 225×225，组件远大于此）；拿它的 `getBoundsInScreen()`；
- 读不到就回退到估算，界面上标「估算」。

### 权限与代价

- 一次授权；Android 13+ 侧载安装需要「允许受限设置」才能开；
- 有 root 时可以一键写入 `enabled_accessibility_services`（要明确告知、可一键关）；
- 服务常驻但事件类型可以只留窗口状态，几乎没有耗电；
- 只取我们自己组件的 bounds，不落盘其它窗口内容。

### 风险

- 个别桌面可能把 workspace 标成 `importantForAccessibility=no`，节点树被折叠。
  K90 没有；ColorOS / 荣耀需要一次真机验证。
- 组件在别的页面时不存在，读到的是上一次缓存——和现在 LSPosed 探针的边界一样。

## 4. 路线 B：root 侧 `dumpsys activity top`（次选）

`dumpsys activity top` 会打印当前前台 Activity 的 `View Hierarchy:`，包含类名、id、
以及每个 View 的 `left,top-right,bottom`（**相对父容器**，要沿缩进累加）。

- 定位：找 `io.github.okaidev.pickupcode:id/widget_root_v2` 的祖先 `AppWidgetHostView`，
  或直接取 `widget_root_v2` 的累加矩形；
- 时机：我们的 Activity `onStop` 之后 ~1s，桌面已经在前台，跑一次 `su -c dumpsys activity top`；
- 优点：不需要新权限、不依赖注入、不受 USAP 影响；
- 代价：只在前台是桌面时有效；输出几百 KB，解析 0.1~0.3s；
- **待验证**：HyperOS 是否保留完整 View 树、是否只 dump focused activity。

## 5. 路线 C：系统数据反推（兜底）

- `AppWidgetManager.getAppWidgetOptions(id)` 的 `OPTION_APPWIDGET_MIN_WIDTH/HEIGHT`
  是桌面按格子算出来的**当前实际尺寸（dp）**，比声明的 minWidth 准；
- `favorites` 库给 `cellX/cellY/spanX/spanY`；
- 由「尺寸 ÷ span」得到格子边长，由「(屏宽 − 列数×格子)/2」得到左右留白；
- **纵向原点仍然拿不到**（桌面顶部留白是 DeviceProfile 的运行时值），只能估。

K90 的抓取里，图标格是 `37 + 225.2×5`，而 4.1 代码按 `1200÷5 = 240` 算——
两者对不上，说明「屏宽÷列数」是拟合值，换机器/换主题就会偏。这条路只能把尺寸变准。

## 6. 路线 D：截图里找自己（不推荐）

组件是半透明卡片，颜色随壁纸变；模板匹配 / 边缘检测都受壁纸干扰，
成本高、稳定性差。除非前面几条都失败，不做。

## 7. 更正后的第一步：补作用域，而不是重启桌面

2026-11-13 复查发现，桌面压根不在本模块的 LSPosed 作用域里（见第 2 节更正）。
所以正确的顺序是：

1. LSPosed 管理器里给本模块勾上 **系统桌面**（包名用 `PackageManager` 查默认桌面，别写死 `com.miui.home`）；
2. 重启桌面进程（`am force-stop com.miui.home` → 回桌面）或重启手机；
3. 看探针有没有写文件：

```bash
adb shell "su -c 'cat /data/user/0/com.miui.home/files/pickup_widget_rect.txt'"
adb shell "su -c 'grep -i com.miui.home /data/adb/lspd/log/modules_*.log'"
```

有了文件，4.1 的 `LauncherProbe` 直接复活，不需要关 USAP、也不需要无障碍。
作用域要**保持**：覆盖安装如果把它重置成推荐集合，就会再次失效。

## 8. 建议的架构

```java
interface WidgetRectSource {
    Rect read();          // null = 这一路拿不到
    String label();       // "桌面" / "无障碍" / "系统" / "估算"
}
```

优先级：LSPosed 探针 > 无障碍缓存 > root dump > 格子估算。
界面沿用现有文案：`精确对齐` / `按格子估算`，并标出来源。
4.1.x 不动；4.2 做外观改造时一起接。

## 9. 待验证清单（K90 接上后 10 分钟）

1. `am force-stop com.miui.home` 后回桌面，ppid 与 LSPosed 日志（第 7 节）；
2. 桌面在前台时 `su -c dumpsys activity top`，确认 `View Hierarchy` 里有 `AppWidgetHostView`
   和 `widget_root_v2`，并把累加矩形与「显示布局边界」的蓝框对齐；
3. 开一个最小无障碍服务，在正常桌面态 dump 一次，确认能拿到 `d='取件码助手'` 的 host 节点；
4. 三条路拿到的矩形两两比对，误差应 ≤ 2px。
