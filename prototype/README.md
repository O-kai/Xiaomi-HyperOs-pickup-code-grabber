# 取件码助手 · UI 重构原型（HTML）

参照「奶油底 + 米金强调色 + 白色分组卡片 + 悬浮胶囊底栏」的 HyperOS 风格，把现有界面拆成
**4 个 Tab + 2 个子页**，共 10 帧原型（含 2 帧下滚状态）。数字与文案全部取自本项目真实实现。

## 文件

| 文件 | 说明 |
| --- | --- |
| `index.html` | 原型展板：设计 token 说明 + 10 帧手机界面（纯静态 HTML，图标为内联 SVG，无外部依赖） |
| `app.css` | 设计系统 + 全部屏幕样式（颜色变量、卡片、行、开关、分段控件、底栏…） |

## 预览

直接双击 `index.html` 即可（相对路径引用 `app.css`，无需服务器）。
若要手机上看，可在本目录起一个静态服务：

```bash
python -m http.server 8848
# 手机访问 http://<电脑IP>:8848/index.html
```

## 屏幕清单与对应实现

| 帧 | 屏幕 | 对应现有代码 |
| --- | --- | --- |
| ① | 首页 · 体检正常 | `LauncherActivity` 顶部（标题/署名/QQ 群）＋ `refreshHealth()` 体检卡<br>＋ 项目版本信息 / 是否有新版本（读 `PackageManager` + `UpdateCenter`） |
| ② | 首页 · 体检异常 | 同上 + `deployBtn`（一键部署 sqlite3）+ `troubleshootBtn`（排查向导） |
| ③ / ③b | 应用（通知来源 6 + 写入目标下拉 + 环境适配下拉） | `NotiHook.BUILTIN_PKGS`、`showBackendPicker()`、`buildNotiPkgsRowContent()`（P4 后端已就绪、界面未开放） |
| ④ | 规则（官方规则集 / 我的规则 / 黑名单 / 工具） | `assets/rules/rules.json`、`UserRulesActivity`、黑名单卡、`precheckThenOpenChainTest()`、`MissedSmsStore` 错题本、`runTroubleshoot()`、`exportDiag()` |
| ⑤ / ⑤b | 设置（提取与写入 / 个性化 / 常规 / 帮助与关于） | `backendRow`、`tplBox` 待办模板、`notiRow`、`sdwRow` 冻结免疫直写、`netRow` 允许联网、`UpdateCenter`、菜单里的仓库/打赏 |
| ⑥ | 一键链路测试 · 第 1 步 | `ChainTestActivity` 第 1 步（输入 + 发送方 + 填入官方案例） |
| ⑦ | 一键链路测试 · 第 2 步 解析结果 | `ChainTestActivity` 第 2 步（只解析不写库）＋ 第 3 步写入按钮 |
| ⑧ | 用户自定义规则 · 列表 | `UserRulesActivity`（必读提示 / 启用开关 / 规则列表 / 新建 / 手册） |

## 设计 Token（全纯色，无渐变 · 当前主题：B · 雾霾蓝）

| 用途 | 值 | 说明 |
| --- | --- | --- |
| 页面底色 | `#F2F3F5` | 纯色浅灰底（对齐参考图） |
| 卡片 | `#FFFFFF`，圆角 `18dp`，卡间距 `10–12dp` | 阴影 `0 1px 2px + 0 8px 20px -10px rgba(124,94,35,.16)` |
| 状态卡 | `#D6E4F2`（异常态 `#F2D6D2`） | 纯色，不用渐变 |
| 强调色 | `#4A6FA5`，缎带深色 `#3D5C8A`，强调浅底 `#E2EAF4` | 开关、主按钮、选中态、缎带 |
| 文字 | 主 `#191919` / 次 `#3D3A36` / 说明 `#8E8E8E` / 分组标题 `#6B7C92` | |
| 语义色 | 成功 `#3E8E5E`、警示 `#C97F2E`、失败 `#C2543C` | 低饱和，保持暖色系 |
| 字号 | 页标题 26 / 行标题 15.5 / 行说明 12.5 / 分组标题 13.5 / 底栏 11 | 字体 MiSans（回落系统中文字体） |
| 底栏 | 高 72dp、圆角 36dp、距底 **29dp**、**透明磨砂**（`rgba(255,255,255,.42)` + `blur(26px)`）| 内容从底栏下方模糊透过；选中项 = 强调色圆角块 34dp + 白色图标 + 深色标签；无中间按钮 |
| 开关 | 52×31dp，轨道 `#4A6FA5`（开）/ `#DFE5EC`（关），白色圆钮 27dp | |

App 图标底色也全部改为纯色（各 App 品牌色）：菜鸟 `#3D8FE0`、菜鸟裹裹 `#2E9B90`、
京东 `#D4483D`、淘宝 `#F0861F`、拼多多 `#D64840`、顺丰 `#2B2B2B`。

## 配色方案（四选一）

`index.html` 顶部有「配色方案 · 四选一」区块，用真实组件渲染了四套配色。
**当前全站默认 = B · 雾霾蓝**（即 `<body class="t-mist">`）；切换只需换 body 上的主题类：

| 主题类 | 名称 | 底色 | 状态卡 | 强调色 | 底栏选中胶囊 |
| --- | --- | --- | --- | --- | --- |
| `.t-sand` | A · 米金 Warm Sand | `#FBEDE1` | `#F9DDB8` | `#A8813C` | `#EFDFC7` |
| `.t-mist`（**当前默认**） | B · 雾霾蓝 Mist Blue | `#EEF3F8` | `#D6E4F2` | `#4A6FA5` | `#DCE7F2` |
| `.t-teal` | C · 青瓷绿 Teal | `#E9F2EE` | `#CFE8DE` | `#2F7F6F` | `#D5E8E1` |
| `.t-rose` | D · 藕荷玫瑰 Rose | `#F7EDF1` | `#F2D8E0` | `#A65D78` | `#EEDAE2` |

文字色与语义色（成功绿 / 警示橙 / 失败红）四套通用，只换底色与强调色。

Android 资源（当前默认的雾霾蓝）：

```xml
<!-- app/res/values/colors.xml -->
<color name="ui_bg">#F2F3F5</color>
<color name="ui_card">#FFFFFF</color>
<color name="ui_card_soft">#F6F9FC</color>
<color name="ui_hero">#D6E4F2</color>
<color name="ui_hero_warn">#F2D6D2</color>
<color name="ui_hero_ink">#1F3450</color>
<color name="ui_hero_sub_ink">#4A5F7A</color>
<color name="ui_ink">#191919</color>
<color name="ui_ink_2">#3D3A36</color>
<color name="ui_ink_3">#8E8E8E</color>
<color name="ui_group_label">#6B7C92</color>
<color name="ui_accent">#4A6FA5</color>
<color name="ui_accent_deep">#3D5C8A</color>
<color name="ui_accent_ink">#2F4E7A</color>
<color name="ui_accent_soft">#E2EAF4</color>
<color name="ui_track">#E6ECF3</color>
<color name="ui_tab_pill">#DCE7F2</color>
<color name="ui_tg_off">#DFE5EC</color>
<color name="ui_tab_chip">#4A6FA5</color>          <!-- 底栏选中块（= 强调色） -->
<color name="ui_tab_frost">#6BFFFFFF</color>        <!-- 底栏磨砂底（约 42% 白） -->
<color name="ui_line">#E8EDF4</color>
<color name="ui_ok">#3E8E5E</color>
<color name="ui_warn">#C97F2E</color>
<color name="ui_danger">#C2543C</color>
```

## 与旧界面的主要变化

1. **全部改为纯色**：页面底、状态卡、按钮、App 图标底色、单选圆点一律去掉渐变；
   唯一的 `linear-gradient` 是「下滚状态」演示帧顶部的渐隐遮罩（功能性滚动提示，非装饰配色）。
2. **图标全部 SVG 化**：状态卡、清单、工具行、规则卡内的「发送方 / 地点 / 自测通过」等
   一律用内联 SVG（`<symbol>` + `<use>`），不再使用 📞 📍 ✅ 🎓 这类 emoji。
3. **从"一页长列表"改为"4 Tab 分区"**：原先设置页把体检、写入目标、模板、自定义规则、链路测试、
   黑名单、使用说明全塞在一页；现在按用户意图分区——想知道状态去首页，想管规则去「规则」，
   想改功能开关去「设置」。
4. **新增「应用」Tab**：通知来源白名单（6 个 App，逐个可开关）+ 写入目标单选 + 环境适配徽标；
   顺带把后端已实现的 P4「自定义来源包名」开放了界面入口。
5. **动作按钮语义化**：主行动（写入待办 / 检查更新 / 新建规则）用米金实心按钮，
   危险/待处理（排查问题向导）用橙色，成功类（一键部署 sqlite3）用绿色，其余用白色幽灵按钮。
6. **状态卡承载"一眼结论"**：正常态一句话「一切正常 · 已激活」+ Release 缎带；
   异常态换色 + 标 Debug 缎带 + 自动展开六项体检清单，把"哪几项没过"前置。
7. **底部导航胶囊**：滑动时内容从半透明底栏下经过，选中项为强调色浅底胶囊。

## 本轮调整（第 2 轮反馈）

1. **首页设备信息整组移除**：构建时间 / 安卓版本 / 设备型号 / 系统架构 / 框架 & API 五张卡删除，
   需要时可挪到「设置 → 关于模块」作为二级页。
2. **首页新增两条版本信息**：`项目版本信息`（v3.1.0 (310) · 规则集 v5 · MIT License）与
   `是否有新版本`（右侧徽标「已是最新」，副标题写明 3 天一次自动检查、也可手动检查）。
3. **首页反馈入口只留 GitHub 图标**：删除纸飞机图标；QQ 群那行改用文字动作「复制群号」
   （与现有 `copyQqGroup()` 一致），整张卡因此只剩一个图标。
4. **应用页两处改下拉列表**：`待办写入目标` 与 `环境适配` 由单选/徽标行改为下拉（收起时右侧显示当前值
   ＋下箭头）；帧 ③b 展示展开后的浮层菜单，当前项带强调色对勾。
5. **底栏上抬 5px**：`bottom: 14px → 19px`（配色演示块同步 12 → 17px）。

> 说明：帧 ②（体检异常）为聚焦错误路径，省去了版本卡（同一页在帧 ① 已展示）；
> 帧 ③ 内容较长，`写入与适配` 的第二行（环境适配）在帧 ③b 中完整展示。

## 本轮调整（第 3 轮反馈：参考图样式）

按你给的酷安参考图，只改两处，其余保持原样：

1. **页面底色改成参考图的浅灰** `#F2F3F5`（原 `#EEF3F8`）；强调色、语义色、状态卡底色全部不动，
   仍是雾霾蓝这一套（B）。
2. **底栏改版**：白色悬浮胶囊 → **透明磨砂胶囊**（`rgba(255,255,255,.42)` + `blur(26px) saturate(1.7)`，
   内容从底栏下方模糊透过）；选中项由「暖底胶囊」改为**强调色圆角块 + 白色图标 + 深色标签**
   （参考图的选中样式）；高度 68 → 72dp、圆角 34 → 36dp。
3. **不加中间按钮**：参考图中间的绿色发布按钮按你的要求不放；底栏仍是 4 项
   首页 / 应用 / 规则 / 设置。

> 实现位置：`app.css` 的「8. 底栏」小节；四套配色各自有 `--tabbar-bg / --tabbar-line / --nav-ink`，
> 换主题时磨砂底与图标色会一起跟着走。

## 本轮调整（第 4 轮反馈）

1. **首页版本信息拆成两张卡**：`项目版本信息`（立方体图标 + 版本号/规则集/许可证 + 右箭头，点进去可看完整构建信息）
   与 `是否有新版本`（更新图标 + 检查说明 + 「已是最新」徽标）各自独立成卡。
2. **应用图标改为本地 SVG 文件**：应用页 6 个图标不再用「字母色块」，改为
   `prototype/icons/*.svg`（`<img class="app-ico">` 引用，96×96 画布、显示 38dp、圆角 11dp）。
   图标来源与替换方式见 [`icons/README.md`](icons/README.md)。
3. **iconfont 下载需登录**：`iconfont.cn` 的图标详情/下载接口对匿名请求返回
   `{"error_code":"LOGIN REQUIRED"}`，因此无法自动抓取；已在 `icons/README.md` 里给出
   每个图标的搜索关键词、命名与尺寸，你从 iconfont 下载后**同名覆盖**即可，无需改代码。
   当前 6 个文件是自绘占位图标（包裹 / 礼盒 / 购物袋 / 货车 / 「淘」字袋），不含第三方商标素材。
4. **底栏再抬高 10px**：`bottom: 19px → 29px`（配色演示块同步 17 → 27px）。
5. **修掉一处图标缺失**：「项目版本信息」引用的 `i-box` 之前只在代码里写、`<symbol>` 没定义，
   导致该行左侧空白；现已补上立方体图标，并清掉 5 个无引用的旧图标（`i-clock / i-cpu / i-layers / i-send / i-signal`）。

## 本轮调整（第 5 轮反馈：官方图标）

- **应用图标保持「本地文件 + 同名覆盖」**：6 个 `<img>` 现在带 PNG 回退
  （`src="icons/x.svg"` → 失败时自动用 `icons/x.png`），所以从 iconfont 下 SVG 或 PNG 都能直接用。
- **iconfont 无法程序化下载**：搜索页返回反爬空壳、搜索接口返回 HTML、详情接口返回
  `LOGIN REQUIRED`；已把每个图标的 iconfont 搜索直链、命名与尺寸整理进
  [`icons/README.md`](icons/README.md)，登录后约 30 秒即可换好一个。

## 本轮调整（第 6 轮反馈：官方应用图标）

- **应用页改用官方应用图标**：菜鸟 / 京东 / 淘宝 / 拼多多 / 顺丰 5 个来自 App Store 公开接口的官方美术图
  （180×180 PNG，抓取脚本 [`icons/fetch-icons.py`](icons/fetch-icons.py) 可重跑，来源记录在 `icons/official-sources.json`）；
  菜鸟裹裹在 iOS 已并入「菜鸟」、无独立官方图标，保留自绘占位。
- **自绘图标降级为兜底**：原 6 个自绘 SVG 移入 `icons/fallback/`，页面引用改为
  `src="icons/x.png"` + `onerror` 回退到 `icons/fallback/x.svg` —— 删掉官方 PNG 即自动回到「无第三方素材」版本。
- **图标源实测**（详见 [`icons/README.md`](icons/README.md)）：iconfont 下载需登录（详情接口返回 `LOGIN REQUIRED`）；
  thesvg.org 可访问但 7,436 个图标里只有 `taobao`，覆盖不全。

## 待确认 / 取舍

- **配色已定**：全站默认 **B · 雾霾蓝 Mist Blue**；A/C/D 三套仍在 `index.html` 顶部的
  「配色方案」区块里保留，随时可切换。
- **「个性化」组（外观 / 模糊效果 / 语言）是本次新增建议项**，纯 UI、实现成本低；
  若不想加，删掉该组即可（其余不受影响）。
- 第 3 个 Tab 现命名为 **「规则」**（参考图该位是「拓展」）；如需保留「拓展」字样，
  仅改标签文字即可。
- 首页两张统计卡（适配应用 6 / 识别来源 9）目前是**静态事实值**；
  若希望显示「今日提取 / 累计写入」这类动态计数，需要新增埋点统计（当前代码未记录次数）。
- 子页（链路测试 / 自定义规则）沿用返回箭头 + 无底栏的形态，与 Tab 页区分层级。
- 原型中的设备信息（机型 / 安卓版本 / 构建时间 / LSPosed 版本）为示例数据，
  实现时按现有 `LauncherActivity` 的读取方式（`PackageManager` + `Build`）动态填充。

## 迁移建议（Java 侧）

现有界面是纯 `LinearLayout` 代码组装（无 XML 布局、无 RecyclerView），建议沿用同一路径，
先把本原型的样式抽成工具方法，避免样式散落在各处：

```
UiKit.card(ctx)                      // 白卡（圆角 18dp + 阴影）
UiKit.groupLabel(ctx, "个性化")       // 分组小标题
UiKit.row(ctx, iconRes, title, sub, accessory)  // 行：图标 + 标题 + 说明 + 右侧配件（开关/箭头/文字）
UiKit.toggle(ctx, checked)           // 米金开关（可用自定义 Drawable 或 SwitchCompat 着色）
UiKit.badge(ctx, text, style)        // 徽标：gold / ok / mute / warn
UiKit.btn(ctx, text, style)          // 按钮：primary / ok / warn / ghost
```

分片实现顺序建议：先做「首页 + 设置」（信息密度最高、也最贴近参考图），
再做「规则」聚合页（把现有卡片搬进新容器），最后做「应用」Tab（需要接 P4 白名单后端）。
每步都可独立编译、单独看效果，不必一次改完 `LauncherActivity` 2062 行。
