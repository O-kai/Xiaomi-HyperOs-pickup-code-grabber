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

## 0.1 先定精度，再定方案（2026-11-13）

「预览要多准」一直没定义，所以才会在四条取数路径之间来回换。三个档位：

| 档位 | 含义 | 实现 | 代价 |
|---|---|---|---|
| A 看起来像 | 卡片大小对、背景大致跟着走 | 现有格子估算 | 位置偏 78px / 44px（本机），表现为壁纸裁切错位，不是卡片变形 |
| B 1:1 | 和桌面像素级重合 | 无障碍读 bounds | 一次授权；要在桌面在前台时刷新；跨 ROM 需验证 |
| C 一次性准 | 校一次、存下来 | 截图量边 / 手动对框 | 不需要权限；换网格、换主题、换机要重校；不实时 |

4.2 的建议：默认 A，把「精确对齐」从默认承诺降级成可选增强；外观（配色 / 色阶 / 描边）先做，
它不依赖任何取数路径。B 等外观定型后再决定要不要为它写授权引导。

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

> **2026-11-13 复核（结论不变，反而更确定）**：今天查当前 scope，里面没有 `com.miui.home`。
> 但**不能**据此说「勾上就好」——11-12 的实测里，scope 行确实写进去了
> （`io.github.okaidev.pickupcode | com.miui.home | user_id=0`，模块 `enabled=1`），
> 重启桌面两次 + 重启手机一次之后，LSPosed 日志里仍然没有 `(com.miui.home)[...]` 注入行，
> 探针文件也始终没生成。**有作用域也没注入。**
> 今天缺那一行只能解释「现在为什么没数据」，推不翻上次的结论。
> **不要再把「勾桌面作用域」当成修复方案**（详见第 7 节）。

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

## 7. 桌面注入这条路：已验证，别再试

时间线（都是真机实测，不是推断）：

| 时间 | 动作 | 结果 |
|---|---|---|
| 11-12 | 作用域写入 `com.miui.home`（db 里 `user_id=0`，模块 `enabled=1`） | 配置没问题 |
| 11-12 | 重启桌面进程 ×2、重启手机 ×1 | 探针文件始终没生成 |
| 11-12 | 查 LSPosed 日志 | 没有 `(com.miui.home)[io.github.okaidev...]` 注入行 |
| 11-13 | 再查当前 scope | 那一行已经没了（多半是 4.1.0 覆盖安装重置成推荐集合） |

结论：**加了作用域也不注入**。今天的 scope 缺失只解释「现在为什么没数据」，
不能当成「补上就能好」的理由——那是一次已经做过、已经失败的实验。

剩下的可用路径：无障碍读 bounds（第 3 节）、root 侧一次性校准（第 4/5 节）。
桌面 hook 的代码（`LauncherProbe`）留在仓库里不删，等以后换 LSPosed 版本或 ROM 再验证。

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
