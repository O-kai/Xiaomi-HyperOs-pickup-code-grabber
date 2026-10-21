# UI 重构（v4.0.0 提案）：四 Tab 布局 · 设计系统 · 图标体系

> 日期：2026-10-14 ｜ 基线：`v3.1.0` (versionCode 310) ｜ **状态：工作区改动，未提交**（供作者 review 后决定是否合并）
> 目的：把界面从「单页长列表」重构为「固定头部 + 四 Tab + 悬浮磨砂底栏」，并把配色 / 组件 / 图标
> 收敛成一套可切换的设计系统；同时修掉几个真机暴露的缺陷。
> 设计与实现一一对应：`prototype/index.html`（HTML 原型，可直接双击打开）→ `app/src/io/github/okaidev/pickupcode/UiKit.java`（Android 实现）。

## 〇、结论速览

- **信息架构**：4 个 Tab（首页 / 应用 / 规则 / 设置）+ 2 个子页（一键链路测试 / 用户自定义规则）。
- **设计系统**：`UiKit` 提供四套配色（米金 / 雾霾蓝 / 青瓷绿 / 藕荷玫瑰，默认雾霾蓝，可在「设置 → 外观」切换）
  与统一组件（卡片 / 行 / 开关 / 徽标 / 按钮 / 悬浮磨砂底栏 / 弹窗），改配色不会散落到各页面。
- **图标体系**：40 个线条图标（矢量，由原型 SVG 自动生成）+ App 图标三级回退（官方 PNG → 真实矢量 → 自绘兜底）。
- **功能逻辑零删减**：体检六项、写入目标切换（含未安装校验）、待办模板及防呆校验、用户规则、黑名单、
  错题本、排查向导决策树、诊断报告导出、通知点击复制 / 已取件……全部逻辑原样保留（只换壳）。
- **顺带修复的真实缺陷**：作用域检查的 su 探测假错误、作用域检查的配置库路径写死、自定义规则页保存后整页被清空。
- **验证**：Android 14（API 36）模拟器 + 真机（Redmi Note 11T Pro · Vector · MIUI V140）实跑，**0 次崩溃**；
  四个 Tab、弹窗、模板编辑器、自定义规则页逐屏截图核对。

---

## 一、文件清单

### 1.1 新增

| 文件 | 说明 |
| --- | --- |
| `app/src/.../UiKit.java` | 设计系统：四套配色 + 组件构建器 + 统一弹窗套件 + 悬浮底栏 |
| `app/src/.../AppIcons.java` | App 图标加载（assets PNG → 真实矢量 → 自绘兜底 三级回退 + 名称映射） |
| `app/res/drawable/ic_*.xml` ×40 | 线条图标（矢量）：底栏 4 个 + 行图标 36 个 |
| `app/res/drawable/app_cainiao_guoguo.xml` | 菜鸟裹裹真实图标（矢量，由 `prototype/icons/cainiao-guoguo.svg` 生成） |
| `app/res/drawable/app_fallback_*.xml` ×6 | 六个 App 的自绘兜底图标（不含第三方素材，可安全分发） |
| `app/assets/appicons/*.png` ×5 | 官方位图图标（菜鸟 / 京东 / 淘宝 / 拼多多 / 顺丰，App Store 公开接口抓取） |
| `prototype/` | **设计原型**：`index.html`（10 帧界面 + 配色方案对比）、`app.css`、`README.md`、`icons/`（图标与抓取/提取脚本）、`tools/gen-android-icons.py`（原型 SVG → Android 矢量图） |
| `docs/screenshots/home-v4.png` | 新版首页截图（README 引用） |
| `docs/19-ui-v4-refactor.md` | 本文 |

### 1.2 修改

| 文件 | 改动 |
| --- | --- |
| `LauncherActivity.java` | **主要重构对象**：单页 → 四 Tab；底栏改悬浮叠层 + 底部渐变遮罩；弹窗全部换成 `UiKit` 自绘套件；体检清单只显示「通过 / 未通过」；模板效果预览用矢量图标代替 emoji；按钮配矢量图标；**去掉滚动条**；清理界面 emoji |
| `ChainTestActivity.java` | 配色跟随主题（`UiKit.palette`）、按钮去 emoji、写入异常弹窗改用统一套件 |
| `UserRulesActivity.java` / `DonateActivity.java` | 同上换肤；另：**去掉滚动条**（含规则编辑弹窗内的滚动区） |
| `UserRulesActivity.java` | 整页统一到设计系统（必读卡 / 开关 / 规则卡 / 按钮 / 11 个弹窗）；**修掉「保存后整页被清空」的 bug**；清理 emoji |
| `DonateActivity.java` | 配色跟随主题、去 emoji |
| `PickupExtractor.java` | 新增 `getActiveRules()`（供界面展示规则集词表规模，只读） |
| `Diagnostics.java` | 作用域检查文案改为「无法自动核对 ≠ 有问题」；`PROBE_MARK` / `suRan` / `suStdout` 对外可用；su 候选路径补 `/debug_ramdisk/su` 等；清理提示语 emoji |
| `Repair.java` | `lsposedStatus()` 重写为**结构自适应**：`sqlite_master` 读结构 → 按真实列名查询 → 分支变体退化 → 失败时把结构写进报告（详见 §五.1） |
| `TodoWriter.java` | 默认模板去掉 `📦`（**写入待办的文本不再带 emoji**）；模式标题兜底同步 |
| `Notifier.java` | 通知标题 / 正文去 emoji |
| `NotesBackend.java` | 「标记已取件」写入笔记的标记 `（✅ 已取件）` → `（已取件）` |
| `UpdateCenter.java` | 更新提示弹窗 / Toast 去 emoji |
| `README.md` | **只替换「效果截图」第 1 张为新版首页**（README 正文与原有样式保持原样） |
| `docs/screenshots/README.md` | 素材索引新增 `home-v4.png` 一行（其余保持原样） |

> 注：`local.properties`（SDK 路径）与 `app/build/` 均已在 `.gitignore` 内，不会进提交。

---

## 二、设计系统（`UiKit`）

### 2.1 配色（四套，可在「设置 → 外观」切换，持久化在 `dedup` 偏好）

| 主题 id | 名称 | 底色 | 状态卡 | 强调色 | 底栏选中块 |
| --- | --- | --- | --- | --- | --- |
| `mist`（默认） | 雾霾蓝 | `#F2F3F5` | `#D6E4F2` | `#4A6FA5` | `#4A6FA5` |
| `sand` | 米金 | `#FBEDE1` | `#F9DDB8` | `#A8813C` | `#A8813C` |
| `teal` | 青瓷绿 | `#E9F2EE` | `#CFE8DE` | `#2F7F6F` | `#2F7F6F` |
| `rose` | 藕荷玫瑰 | `#F7EDF1` | `#F2D8E0` | `#A65D78` | `#A65D78` |

语义色（成功 `#3E8E5E` / 警示 `#C97F2E` / 失败 `#C2543C`）四套通用，不随主题变化。

### 2.2 组件

卡片（白底 18dp 圆角 + 轻阴影）· 分组小标题 · 行（图标 + 标题 + 说明 + 右侧配件）·
开关（自绘 52×31dp，强调色轨道）· 徽标（ok / mute / accent / warn）· 按钮（primary / ok / warn / ghost / ghostLine，
可带矢量图标）· 分段控件 · 弹窗套件（圆角卡片 + 图标标题 + 自动纵向排布的按钮）· 悬浮底栏。

### 2.3 底栏规格

高 72dp、圆角 36dp、左右 12dp、距底 29dp；**用 FrameLayout 悬浮在内容之上**（内容从底栏下方滚过，
不是占一条独立空间）+ 底部三段渐变遮罩（底栏下方留实底，覆盖区轻微淡化）；
**半透明白底 85%**（Android 无法对背后内容做 backdrop blur，以半透明近似；
「设置 → 模糊效果」可切换为 100% 纯实底）；选中项为强调色圆角块 + 白色图标。

> 不透明度调过三轮：42%（原型值，真机上卡片文字与底栏文字互相穿透）→ 95%（偏实、失去悬浮观感）
> → **85%（最终，可隐约看到底下内容且可读性良好）**。

---

## 三、四 Tab 的信息架构（旧 → 新 对照）

| 新位置 | 内容 | 来源（v3.1.0 的位置） |
| --- | --- | --- |
| **首页** | 状态卡（体检结论，点开见六项清单）· 双统计卡 · 项目版本信息 · 是否有新版本 · 反馈入口 | 原顶部标题区 + 体检卡 + （新增）版本/更新两块 |
| **应用** | 通知来源 6 个 App（官方图标）· 添加自定义来源 · 待办写入目标 · 环境适配 | 原「基础设置」卡的写入目标 / 通知 / 联网 + 原「更多设置」里的 P4 自定义来源 |
| **规则** | 官方规则集 · 我的规则（自定义规则 / 新建）· 黑名单 · 工具（链路测试 / 错题本 / 排查向导 / 诊断报告） | 原「用户自定义规则」卡 + 黑名单卡 + 一键链路测试按钮 + 右上角 ⋮ 菜单里的错题本 |
| **设置** | 提取与写入（写入目标 / 待办模板 / 通知取件 / 冻结免疫直写）· 个性化（外观 / 模糊效果 / 语言）· 常规（自动检查更新 / 检查更新 / 更新日志）· 帮助与关于（用户协议 / 关于模块 / 打赏 / 仓库） | 原「基础设置」+「待办模板」+ ⋮ 菜单（检查更新 / 打赏 / 仓库）+（新增）个性化与关于 |
| 子页 | 一键链路测试 · 用户自定义规则 | 原独立 Activity（功能不变，仅换肤） |

> 原有的「⋮ 菜单」已取消：里面的四项都能在新结构里找到更明确的位置。

---

## 四、图标体系

### 4.1 三级回退（`AppIcons`）

```
assets/appicons/<slug>.png        ← ① 官方位图（现有菜鸟 / 京东 / 淘宝 / 拼多多 / 顺丰）
        ↓ 缺失时
res/drawable/app_<slug>.xml       ← ② 真实图标矢量版（现有菜鸟裹裹；由 prototype/icons/<slug>.svg 生成）
        ↓ 缺失时
res/drawable/app_fallback_<slug>.xml ← ③ 自绘兜底（不含第三方素材）
```

去掉 ① ② 两级即回到「无第三方素材」的自绘版本 —— 便于发行时做合规取舍。

### 4.2 生成链路（改设计后可重跑）

```bash
python prototype/tools/gen-android-icons.py     # 原型 SVG → app/res/drawable/*.xml
```

### 4.3 图标来源与实测结论（细节见 `prototype/icons/README.md`）

| 来源 | 结论 |
| --- | --- |
| **App Store 公开接口** | ✅ 可用：菜鸟 / 京东 / 淘宝 / 拼多多 / 顺丰 5 个官方图标（`prototype/icons/fetch-icons.py` 可重抓） |
| **iconfont.cn** | ❌ 下载需登录：搜索页返回反爬空壳、搜索接口返回 HTML、详情接口 `LOGIN REQUIRED` |
| **thesvg.org** | ❌ 覆盖不足：全量 7,436 个图标里只有 `taobao`，无京东 / 拼多多 / 菜鸟 / 顺丰 |
| **国内应用商店（应用宝 / 豌豆荚 / 小米 / 酷安）** | ❌ 页面均为前端异步渲染或已改版，HTML 中拿不到图标地址 |
| **菜鸟裹裹（com.cainiao.guoguo）** | 无可公开抓取的素材（iOS 端已下架）；现用**用户提供的矢量图标**；如需 1:1 位图可从手机提取：`python prototype/icons/extract-from-device.py com.cainiao.guoguo cainiao-guoguo` |

---

## 五、顺带修复的缺陷

### 5.1 LSPosed 作用域检查在真机上报假错误（`Repair.lsposedStatus`）

- **现象**（真机 Redmi Note 11T Pro · Vector）：`[✗] LSPosed 作用域: 无法读取 LSPosed 配置（… sh: /su/bin/su: inaccessible or not found）——LSPosed 未安装或路径不同`，
  而同一份体检里 root、sqlite3、笔记库都是 ✓ —— 明显自相矛盾。
- **根因（两层）**：
  1. `suExecPublic` 会依次尝试多个 su 路径，判定标准是「stdout 非空」；若都为空则返回**最后一次尝试**的结果（通常是不存在的 `/su/bin/su`）→ 报出一个与真实故障无关的假错误。
     `Diagnostics` 内部有明确约定（命令尾部加 `2>&1; echo __PICKUP_PROBE_END__` 哨兵），本类 4 个探针都遵守，但这个查询漏了。
  2. 配置库路径写死为 `/data/adb/lspd/config/modules_config.db`，LSPosed 分支（如 Vector）可能在别处。
- **改法（第一轮）**：查询加哨兵；配置库改为**多路径 + `find /data/adb -maxdepth 5 -name modules_config.db` 兜底**；
  SQL 增加 `DBOK=` 探针；失败文案改为「**无法自动核对（原因）…这不代表配置有问题**」。
- **第二轮（真机报告暴露的更底层原因）**：Vector 的配置库**结构已变**——报告里的原始输出显示
  `no such table: modules_state`、`no such column: module_pkg_name`（`scope` 表还在，但列名换了）。
  即"模块未启用"其实是**读库方式对不上**，而非用户没勾选。
- **改法（第二轮）**：改为**结构自适应**——
  ① 先读 `sqlite_master` 拿到真实表名与建表语句（列名）；
  ② 按识别出的列名拼查询（模块表按「包名列 + 启用列」识别，作用域表按「目标包名列 + 关联列」识别，
  支持 `module_id` 这种关联式结构）；
  ③ 识别不出模块状态表时退化为「存在作用域记录即视为启用」，**但作用域比对照做**（避免误报"齐全"）；
  ④ 全部识别失败时不下结论，并把**完整结构写进诊断报告**，便于一次性适配。
- **效果**：真机复测从"假错误"→"能读到库/能给出结构"→"按实际结构判定"；若仍不行，报告里已带结构，一次即可定位。

### 5.2 用户自定义规则页：保存后整页被清空

- **现象**：新建 / 编辑 / 启用规则后，页面只剩规则卡，标题、开关、说明全部消失。
- **根因**：`persistAndRefresh()` 里写的是 `renderList((LinearLayout) statusLine.getParent())`，
  而 `statusLine` 的父容器是**整页根布局** → `removeAllViews()` 把整页清空。
- **改法**：列表容器提为字段 `listBox`，只重绘列表。

### 5.3 界面与写入文本里的 emoji

- **界面**：按钮 / 标题 / 弹窗 / Toast / 通知共清理 100+ 处（`🔍 📦 ⚠️ ✅ 💾 ↩️ 🧩 ✏️ 🎓 📤 📥 🗑️ …`）。
- **写入侧**：默认待办模板 `📦 取件码 {code}｜…` → `取件码 {code}｜…`；「标记已取件」写入笔记的标记 `（✅ 已取件）` → `（已取件）`。
- 保留：`Diagnostics` 正则里用于匹配模板行的 `📦`（**功能字符**，删了会影响识别）、代码注释里的图示、`✓`/`✗` 文本符号。

### 5.4 弹窗系统不统一

系统 `AlertDialog`（绿色单选圆点、无主题色）→ 自绘套件：圆角卡片 + 矢量图标标题 + 强调色实心主按钮 + 幽灵次按钮；
长文案自动改为纵向按钮排列。主界面 13 个弹窗、自定义规则页 11 个弹窗全部替换。

### 5.5 体检清单信息过载

失败项原本把完整错误详情塞在行尾（长串换行、与绿色行排版不一致）→ 现在只显示「未通过」，
具体原因交给「排查问题向导」。

---

## 六、已知取舍与后续建议（交给作者判断）

1. **通知来源的内置 App 不能逐个关闭**：界面上是「内置」徽标而非开关。逐个关闭需要 Hook 侧
   （`NotiHook` 白名单）配合，本次刻意没有改动 Hook 逻辑以免影响核心抓取；后端 P4「自定义来源包名」已可用。
2. **底栏磨砂是半透明近似**：Android 没有 backdrop blur；如需真磨砂要在 API 31+ 用 `RenderEffect` 对内容区做模糊（有性能开销）。
3. **诊断报告的 logcat 提取**：真机上 root 下取日志会被 PATH / SELinux 拦（`/system/bin/logcat: inaccessible`，
   绝对路径也一样）。已加固为**依次尝试三种写法**（绝对路径 / 显式补 PATH / `/system/xbin/logcat`），
   并在这段报告里写明"若为空请在电脑上用 `adb logcat -d -s PICKUPDEBUG:*` 导出"——
   避免继续给人"无日志 → Hook 从未触发"的假结论。
4. **Vector 上的作用域检查**：修复后仍可能落到「库可读但查不到本模块启用记录」的第三种状态——
   需要真机确认 Vector 的库结构；若确实不同，把 `SELECT` 的列名适配一次即可。
5. **版本号未动**：仍是 `3.1.0 (310)`。合并前建议 bump `versionCode/versionName`（UI 重构属于大版本变更）。
6. **配色切换是新增能力**：若作者认为"模块不该有主题设置"，删除「设置 → 个性化」组即可（其余不受影响）。

---

## 七、构建 / 验证 / 回滚

```bash
# 构建（本机已装 SDK 时；local.properties 指向 SDK 路径，已被 gitignore）
gradle assembleDebug          # 或 ./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 原型预览（可选，纯静态）
python -m http.server 8848    # 然后打开 http://127.0.0.1:8848/index.html
```

**验证建议（装机后逐项对照）**：四个 Tab 切换 · 体检清单展开/收起 · 写入目标切换弹窗 · 待办模板内联编辑
（含自定义保存校验）· 通知取件开关（含作用域提示）· 黑名单就地编辑 · 一键链路测试三步 · 用户自定义规则
（新建 → 自测 → 保存）· 外观切换四套配色 · 深色状态栏下的底栏可读性。

**回滚**：本次改动全部停留在工作区（未提交）。
`git checkout -- .` 可整体丢弃，或按 §1.2 的表逐个文件还原；新增文件直接删除即可。

**建议的分支与提交信息**（供参考）：

```bash
git switch -c feature/ui-v4-refactor
git add -A && git commit -m "feat(ui): 四 Tab 布局重构 + UiKit 设计系统 + 矢量图标体系

- 主界面从单页长列表改为首页/应用/规则/设置四 Tab，逻辑零删减
- 新增 UiKit：四套配色（可切换）、统一卡片/行/开关/徽标/按钮/弹窗/悬浮磨砂底栏
- 图标：40 个线条图标（原型 SVG 自动生成）+ App 图标三级回退（官方 PNG/真实矢量/自绘兜底）
- 修复 LSPosed 作用域检查的 su 探测假错误与配置库路径写死
- 修复用户自定义规则页保存后整页被清空
- 清理界面与写入文本中的 emoji（默认待办模板同步去 emoji）"
```
