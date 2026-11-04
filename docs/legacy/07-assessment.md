# 07 · 项目评估与架构方案（草案）

> 本文档基于对内部历史资料（`docs/01-06` + `legacy/` 旧版代码，未随仓库公开）的完整通读，
> 对"取件码助手"项目做全面评估，并提出建议的目标架构与推进路线。
> 状态：**草案 → 后续决策已定稿（见第九节）**。完整演进历程见 `docs/HISTORY.md`。
> 注：文中示例短信的驿站地址已脱敏。

---

## 一、项目全貌还原

**产品**：取件码助手（PickupCodeGrabber）—— 一个运行在小米手机（MIUI/HyperOS）上的
LSPosed 模块：

- 捕获快递取件短信（主通道，多点 Hook）
- 可选捕获菜鸟/丰巢/京东/顺丰等 App 的通知（副通道，NotificationListenerService）
- 从文本中提取取件码
- 存储：JSON 文件（用户可读）+ SharedPreferences（去重）
- 可选输出：写入小米便签（便签同步）
- 通知弹出 + 点击复制取件码
- 模块自带设置页（LauncherActivity）

**目标设备环境**：REDMI K90 Pro Max，16+16GB / 1TB，Magisk Root，LSPosed 原版（非 Zygisk），
HyperOS（具体版本号未确认），构建环境为 Termux（此前无 PC）。

**资产**：

| 资产 | 说明 | 状态判断 |
|---|---|---|
| `docs/01-requirements.md` | 需求清单（58 条） | 信息完整，但部分结论有误（见第四节） |
| `docs/02-tech-design.md` | 技术方案（Hook 点/解析/便签/构建） | 结构完整，同上有误 |
| `docs/03-files.md` | 文件清单 | 与 legacy 实际内容不符（文档所述文件路径多在 `/sdcard/Download/...`，legacy 中是 Gradle 工程结构） |
| `docs/04-issues.md` | 问题清单（18 条） | 核心问题已定位，根因表述需修正 |
| `docs/05-todo.md` | 待办/测试要点 | 可复用为测试提纲 |
| `docs/06-extra.md` | 环境与背景补充 | 重要：用户技术背景、版本演进、V16 未部署 |
| `legacy/pickup-code-grabber/` | Gradle 工程（新版结构） | 可用作基础，但需重构 |
| `legacy/.../build_termux_v3~v13.sh` | 12 个手工构建脚本（旧版管线） | **全部作废**（均含 stub 缺陷，见 4.1） |
| `legacy/.../build_apk.sh` | SDK 手工管线脚本 | 作废（同 stub 缺陷） |
| `legacy/.../*.apk` | 旧构建产物 | 有问题的旧物，不能直接用（缺 `assets/xposed_init`，且为旧管线产物） |
| `legacy/.../sync_pickup_codes.sh`、`discover_notes_db.sh` | 便签同步/探测脚本 | 探测思路可用（见 4.7） |

**版本演进**：v12 前（通知监听 + 便签 ID 直写 DB）→ v13（图标/同步/通知优化）
→ v14/v15（手工管线 + 详细日志）→ v16（定位 AbstractMethodError，未部署）。
最后一次实际状态：**模块在 LSPosed 中显示已激活，但入口从未被真正调用。**

---

## 二、做得对的部分（保留）

1. **多点冗余 Hook 思想**：S1/S2/S3 同时注册、任一命中即处理 —— 方向正确，需调整进程归属（4.2）。
2. **基于内容判断而非发件人白名单**：快递号码表维护成本高、易过期 —— 正确，保留。
3. **正则支持横线码（X-X-XXXX）与纯数字**（legacy 版）：比 docs v2 只有纯数字更贴近真实短信，
   应以 legacy 正则为基础合并升级。
4. **用户可读 JSON 存储**：`/sdcard/Documents/pickup_codes.json` 是很好的"看得见"存档，保留
   （可同时做模块内列表，见 5）。
5. **Gradle 工程结构（legacy）**：`compileOnly 'de.robv.android.xposed:api:82'` —— 这是
   官方推荐依赖，**正是根治历史 Bug 的正确姿势**（见 4.1）。

---

## 三、结论性纠正：历史最大坑的根因要重定性

`AbstractMethodError`（问题 4）的**表面结论**是"stub 中 LoadPackageParam 放错了类"，
**本质结论**是：

> **一切手工 stub 都是错的。** LSPosed 运行时要求的接口签名是
> `handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam)`，
> 该类的真实定义只在官方 `api-82.jar` 里。只要编译期不用官方 jar，手写 stub 要么签名不对
> （AbstractMethodError），要么字段不全（编译期/运行期意外）。

证据：12 个 `build_termux_*.sh` + `build_apk.sh` 里，**无一例外**都把入口参数写成
`IXposedHookLoadPackage.LoadPackageParam`（已逐一 grep 确认），即使部分脚本创建了
`callbacks/XC_LoadPackage.java` 也没被入口引用 → 全部命中 AbstractMethodError。
而 legacy 的 Gradle 工程用官方 `api:82`，签名天然正确。

**结论：废弃"修 stub"路线（v14/v15/v16 的方向），改为统一使用官方 API 构建。** 若在 PC 上
构建（Gradle/Android Studio），该问题直接消失；若坚持 Termux，也改为下载官方 `api-82.jar`
替换手工 stub（一处下载，终身正确），而不是继续修补 stubs。

---

## 四、需要修正/推翻的技术判断

### 4.1 自研 stub → 官方 API（见第三节，最高优先级）

### 4.2 Hook 点的进程归属（docs 02 的作用域表有错）

| Hook 点 | 真实归属进程 | 应勾选作用域 |
|---|---|---|
| `com.android.internal.telephony.SmsBroadcastReceiver.onReceive` | **com.android.phone**（电话进程，非 systemserver） | `com.android.phone` |
| `com.android.mms.transaction.SmsReceiverService.handleSmsMessage` | com.android.mms（短信应用进程） | `com.android.mms` |
| `com.android.mms.transaction.SmsReceiverService.onReceive` | com.android.mms | `com.android.mms` |

- 作用域最小化：`com.android.phone` + `com.android.mms` + `system`（兜底，写 try-catch 即可）。
- 不再勾选 com.android.systemui / provider / 各类无关进程 —— 减小日志噪声与失败数。
- `handleLoadPackage` 必须按 `lpparam.packageName` 分流注册 Hook（只在该进程存在对应类时
  findClass），而不是所有进程盲目尝试。

### 4.3 `param.thisThread` 不是 Context（legacy XposedEntry 中的隐性 bug）

`doProcess` 里 `(Context) param.thisThread` 会抛 ClassCastException（被 catch 吞掉），
导致**任何取件码都没有真正保存过**。正确取 Context：
- Hook `onReceive(Context, Intent)`：直接取 `args[0]`；
- Hook 实例方法 `handleSmsMessage`：可在 `beforeHookedMethod` 里 `param.thisObject` 不行（是
  Service 实例，可用其 `getApplicationContext()`）。

### 4.4 编码三重 fallback（UTF-8/GB2312）帮倒忙

- Android 的 `SmsMessage.createFromPdu()` 已经按 PDU 的 DCS（数据编码方案：GSM-7bit /
  UCS-2 等）正确解码，**运营商中文短信本身不是"GB2312 字节塞进文本"**。对
  `getMessageBody()` 再做 UTF-8/GB2312 转码，会把本来正确的内容二次破坏。
- 真正要处理的坑是：
  1. **长短信多段拼接**：一条短信可能拆成 2~3 段 PDU，每段各自广播。只取第 1 段 `getMessageBody()`
     会截断内容（取件码常在最后一段）。必须按顺序拼接所有段的正文（或至少收集完整后处理）。
  2. 个别 ROM 的乱码：仅当解码结果含乱码特征（`�`、大量 `?`、无法通过取件码/关键词校验）时，
     才尝试 `raw.getBytes("ISO-8859-1")` → GBK/GB2312 兜底。兜底是"最后手段"，不是默认路径。

### 4.5 去重是核心优先级，不是"后续升级"

三个 Hook 点 × 多进程 = 同一条短信理论上被处理 1~6 次。没有去重就会：重复写便签、重复弹通知、
重复计入统计。设计两层：
- **捕获层指纹**：`sender + body(全文) + 收到时间±容差` 哈希，进程内 LRU（快速内存去重）；
- **处理层幂等**：落库前以 `指纹` 查自有存储（唯一事实源），命中即丢弃。
- 指纹入库后永不删除（或保留 N 天），避免"同一件包裹的补发短信"重复弹。

### 4.6 跨进程存储：SharedPreferences 行不通

LSPosed 模块代码运行在**被 Hook 应用自己的进程和 UID** 里，`context.getSharedPreferences()`
写的是 com.android.mms / com.android.phone 的数据目录，不是模块自己的 —— 多进程之间还不互通。
正确做法（推荐）：
- 模块 App 内建 **ContentProvider**（`content://com.pickupcode.grabber.provider/...`，
  `exported=true` + 自定义权限或免权限）。所有 Hook 进程通过它：上报事件、查询去重指纹、
  取配置。**模块 App 的数据目录 = 唯一事实源。**
- 可选辅助：`/data/adb/pickup/` 下放一份镜像 JSON（Magisk root 可读写，全进程可见，
  便于 adb/电脑查看）。

### 4.7 小米便签写入：三条路线，排序如下

| 路线 | 方式 | 评估 |
|---|---|---|
| A. Hook `com.miui.notes` 进程，调用其自身 ContentProvider.insert/内部 API | 在便签进程内执行，走 App 自己的逻辑（缓存/同步/webview 都正确） | **最稳，首选**。需要在 LSPosed 勾选 `com.miui.notes` 作用域 |
| B. root `sqlite3` 直写 notes.db | 有**缓存覆盖风险**：App 内存中的内容可能在下次保存时覆盖你的写入；且不同版本表结构不同 | 次选，且只做"追加/修正"场景，需先跑 discover 脚本确认表结构、并让便签 App 收到数据变更通知（重启 App 验证） |
| C. 外部进程 `ContentResolver.insert()`（猜测的 URI） | URI/列名未知，版本差异大 | 不推荐，弃用 |

- 实机确认命令（第一步就要做）：
  - `pm list packages | grep -iE "note|mms"`（确认便签/短信 App 包名，可能是 `com.miui.notes`
    也可能 HyperOS 改名；短信 App 也可能是 `com.android.mms` 的变体）
  - 对便签提供方（Provider）跑 `dumpsys package com.miui.notes | grep -A20 provider` 拿 authority
  - 在便签 App 中打开"笔记信息"看笔记 ID（legacy 流程已有）
- **便签写入是整条链路中不确定性最高的环节**，建议放在第二阶段单独攻关，不阻塞主链路
  （捕获→提取→通知→自有列表）。

### 4.8 通知：模块自身进程发，不要寄生他人

- 在 Hook 进程里用 com.android.mms 的 Context 发通知，通知归属会显示为"短信"App，且通知权限
  依赖被 Hook 应用，语义混乱。
- 正确做法：Hook 进程只做"捕获+上报"，**通知由模块 App 自己的进程/服务发**：
  - 模块申请 `POST_NOTIFICATIONS`（Android 13+ 运行时申请，设置页里引导）；
  - 通知点击 → **透明 Activity**：复制取件码到剪贴板（前台 Activity 权限天然满足）→
    `Toast`"已复制" → 打开便签/模块列表页；
  - Android 12+ 剪贴板读取时系统会弹"X 已粘贴"提示条，属正常现象，风险低。

### 4.9 权限最小化

- **去掉** `READ_SMS` / `RECEIVE_SMS`：Hook 在 PDU 层面拦截，模块自身进程完全不需要短信权限；
  安装时少两行敏感权限，减少系统"风险应用"警告和用户疑虑。
- 保留：`POST_NOTIFICATIONS`（通知）、`FOREGROUND_SERVICE`（如后台同步服务）、
  NotificationListener 权限（仅当启用副通道）。

### 4.10 通知监听副通道（旧 README 的产品形态）评估

- 优点：无需 Hook 也能捕获"菜鸟/丰巢/京东/SF App"的推送通知，对"只有 App 推送、无短信"
  的场景有用；无 root 也能工作。
- 缺点：需要用户授予"通知使用权"（MIUI 一直有常驻/授权提示）；与短信通道重复度高。
- 建议：**保留为可选开关**，默认关闭；主通道是短信 Hook；它作为 v2 增强，不阻塞主线。

### 4.11 正则规则合并（legacy 为准）

以 legacy `PickupCodeExtractor` 为基础（横线码 + 数字码 + 关键词锚定），合并 docs v2 的
"关键词优先"思想，形成统一规则（按优先级）：
1. `取件码/提取码[：:\s]*([A-Za-z0-9-]{4,20})` —— 关键词锚定，支持横线/纯数字/字母数字；
2. `取件码为/取件码是/([A-Za-z0-9-]{4,20})` —— 口语化变体；
3. 无关键词锚定时，仅在**上下文含快递特征词**（驿站/快递柜/丰巢/菜鸟/取件/包裹到站/请及时取等）
   的句子内匹配 `\d{1,6}-\d{1,4}-\d{1,6}` 或 `\b[A-Za-z0-9]{4,9}\b` —— 避免广告/验证码误报；
4. 预检（contains 快速过滤）保留，但作为"疑似"而非"已确认"，最终确认以匹配结果为准。

### 4.12 检入的旧 APK 不可用

`legacy/.../pickup-code-grabber.apk` 内**没有 `assets/xposed_init`**（且为旧管线产物），
装到手机 LSPosed 不会加载。任何"直接用旧 APK"的方案都无效，一律重新构建。

---

## 五、建议目标架构

```
┌─ 捕获层（被 Hook 进程内，轻量）──────────────────────────────┐
│  Hook S1: com.android.internal.telephony.SmsBroadcastReceiver │  (com.android.phone)
│  Hook S2: com.android.mms.transaction.SmsReceiverService       │  (com.android.mms)
│  Hook S3: SmsReceiverService.handleSmsMessage                  │  (com.android.mms)
│  Hook S4(可选兜底): SmsProvider.insert / 短信数据库写入点      │  (telephony provider)
│  副通道(可选): NotificationListenerService 抓 App 通知         │  (模块自身进程)
│  输出: 归一化事件 { sender, body全文(已拼接), ts, fingerprint }│
└────────────────────────────────────────────────────────────────┘
                    ↓ ContentResolver 上报（模块 Provider）
┌─ 模块 App 进程（核心服务）────────────────────────────────────┐
│  ① 去重（指纹查库，幂等）                                      │
│  ② 编码兜底（仅必要）→ ③ 取件码提取（规则 4.11）→ ④ 来源识别   │
│  ⑤ 存储：SQLite（自有 data 目录）= 唯一事实源，JSON 镜像到 sdcard │
│  ⑥ 输出：通知（自有 channel）+ 点击复制 + 列表 UI              │
│  ⑦ 可选：便签同步（见 4.7 路线 A/B）                           │
└────────────────────────────────────────────────────────────────┘
        ↑ 配置/权限引导/历史列表（LauncherActivity + 设置）
```

**组件清单（建议新工程结构）**：

| 组件 | 职责 |
|---|---|
| `XposedEntry` | 仅做 Hook 注册 + 归一化上报（不写业务） |
| `SmsBridge` | PDU→事件（拼接、取 Context 的正确姿势） |
| `PickupExtractor` | 正则引擎（保留 legacy 思路升级版） |
| `StoreProvider` | 模块自有 ContentProvider（去重/事件/配置事实源） |
| `CaptureService` / 处理器 | 去重→提取→落库→派发 |
| `Notifier` | 通知 + 点击 Activity（复制→跳转） |
| `NotesSyncer` | 便签写入（二阶段） |
| `LauncherActivity` | 设置页 + 取件码列表（搜索/复制/删除可选） |

---

## 六、关键决策点（待用户确认）

1. **构建平台**：这台 Windows PC（Gradle/Android Studio，推荐，一次性根除 stub 问题）
   还是继续 Termux 手机上构建？
2. **小米便签是"必须"还是"可选"**：便签写入是全链路不确定性最高的一环，建议通知+模块内
   列表为主链路、便签同步作为第二阶段可选功能。
3. **通知监听副通道**：保留（可选开关，v2）还是砍掉（纯短信 Hook）？
4. **产品范围**：第一版做"捕获→提取→通知→最近列表→复制"极简闭环，还是包含历史搜索/
   删除/标记已取件等增强？
5. **实际短信样例**：用户日常收到的取件短信格式（菜鸟/丰巢/京东/顺丰/社区团购…），
   决定正则优先级与测试用例。
6. **效果场景描述**：取件码被捕获后，用户希望"发生什么"（看一眼通知？进便签存档？
   电脑同步？）—— 决定输出层设计。

---

## 七、推荐推进路线（分阶段，每阶段可验收）

| 阶段 | 内容 | 验收标准 |
|---|---|---|
| P0 环境侦察 | 确认设备 HyperOS/Android 版本；`pm list packages` 确认 mms/notes 包名；装好构建环境；LSPosed 范围确认 | 环境清单落盘 |
| P1 捕获闭环 | S1/S2/S3 注册 + 上报到 Provider + 日志（无需 UI/便签） | 发一条测试短信，logcat 看到完整正文 |
| P2 提取+通知 | 提取引擎 + 自有通知 + 点击复制 | 收到取件短信 → 通知出现 → 点击 → 剪贴板有码 |
| P3 列表 UI | 模块内取件码列表（历史/复制） | 打开 App 看到历史记录 |
| P4 便签同步 | 按 4.7 路线 A 尝试，失败降级 B；跑 discover 探测 | 便签中出现条目（实机确认） |
| P5 增强 | 去重策略细化、通知监听副通道、配置开关、长短信/边界测试 | 05-todo 测试清单逐项过 |

---

## 九、决策记录（2026-09-03 用户确认）

| 决策点 | 结论 |
|---|---|
| 构建平台 | **暂缓**：先讨论完再定（PC Gradle / Termux 两条路都保留，P1 开工前定） |
| 小米便签定位 | **必须功能，最高优先级**，需要优先攻克（与 v1 极简闭环并行推进） |
| 通知监听副通道 | **保留，作为可选开关**，默认关闭 |
| 第一版范围 | **极简闭环**：捕获→提取→通知→模块内最近列表→点击复制 |

补充说明：极简闭环（P1-P3）与便签攻坚（P4）并行，不互相阻塞；便签攻关的 P0 侦察
（包名/Provider/表结构）可以立即开始，不需要等主链路代码写出来。

---

## 八、风险清单

| 风险 | 等级 | 应对 |
|---|---|---|
| 便签 App 内部接口随版本变化 | 高 | 二阶段攻关；失败则降级为"root SQL 直写"或"仅模块内列表+导出" |
| MIUI/HyperOS 对短信类名/入口的重构差异 | 中 | 多点 Hook + S4 兜底；每个点都 try-catch 打日志 |
| Android 13+ 通知/剪贴板行为变化 | 低 | 模块自身通知 + 前台 Activity 复制 |
| 长短信/多段/特殊编码 | 中 | 拼接完整文本；乱码特征检测 + 兜底解码 |
| 多进程重复处理 | 中 | Provider 指纹去重（核心设计，P1 就落地） |

---

## 十、场景与样例定稿（2026-09-03 用户确认）

**定稿需求**：收到取件信息（**短信或通知栏**均可）→ 提取取件码数字 → **写入小米笔记的
"待办"，一个取件码 = 一条待办，保证不重复**。输出目标形态明确为"待办事项"
（MIUI/HyperOS 笔记 App 的待办能力），不再以"便签"为唯一形态。

用户样例短信（用于正则与测试用例）：

```
1. 【菜鸟驿站】您的包裹已到站，凭22-2-3579到示例市青丘区松源路66号驿站取件。
2. 【菜鸟驿站】您有3个包裹在示例市青丘区松源路66号驿站，取件码为16-4-9626, 15-3-2194, 16-3-0906
```

**由样例暴露的正则适配要点（必须支持，否则必漏）**：

| 场景 | 要点 |
|---|---|
| 样例 1 | 取件码前是"**凭**"字而非"取件码"，且后面直接跟"**到**"字（无空格/标点边界）。legacy 的 `PATTERN_BARE_DASHED` 用标点/空白做边界，**会漏掉这条例子**。需新增：`凭\s*([A-Za-z0-9-]{3,20})(?=到|去|取|领|，|。|$)` |
| 样例 2 | 一条短信含 **3 个取件码**（逗号分隔），每个码都必须提取、各建一条待办。需"簇提取"：关键字锚定后抓取 `[A-Za-z0-9-]+(?:[,，、;；\s]+[A-Za-z0-9-]+)*` 再逐项拆分校验 |
| 关键字变体 | `取件码为` / `取件码是` / `取件码：` / `提取码：` / `自提码` 等都要支持 |
| 码形状校验 | `\d{1,6}(-\d{1,4}){1,2}`（横线码，如 22-2-3579 / 16-4-9626）或 `[A-Za-z]?\d{4,9}`（丰巢/字母数字） |

**去重策略（定稿）**：以"取件码 + 地点指纹（原文中的驿站/柜名+地址片段哈希）"为主要键，
同时保留码值为次键——因为不同驿站/柜可能出现相同短码（4 位码常见重合），只按码值去重会
误拦合法新码；同一地点的同一码重复收到则不再新增待办。

**待办形态**：小米笔记"待办"每条自带完成状态（checkbox），可自然承载"已取件"标记，属于
白送的产品能力。待办在数据库中的存储结构（独立表 vs 笔记 type 字段）需 P0 侦察确认，
这决定便签攻坚路线 A/B 的具体实现。

**技术可行性结论**：捕获（Hook 短信 / 通知监听）→ 提取 → 去重 → 待办写入，链路中只有
"待办写入"依赖第三方私有接口，需实机侦察；其余环节均为成熟稳定的确定性实现。整体方案
**可行**，风险集中在 P0 待确认项（见 4.7 与第八章）。

---

## 十一、P0 侦察计划与工具链状态（2026-09-03）

**工具链现状**：
- 本机（Windows）：无 adb / python / sqlite3 / git；`dl.google.com` 与腾讯/阿里镜像的出站
  下载均被执行环境拦截（baidu 亦不通，判定为沙箱网络策略，非用户网络问题）。
- 方案：由用户浏览器手动下载官方 platform-tools ZIP 放入项目 `tools/` 目录，本机解压后由
  AI 通过 adb 操作手机。备选：Termux 内手动粘贴命令回传输出（仅当下载受阻）。

**P0 侦察命令清单（全部只读，不改动手机任何数据）**：
1. 设备信息：`getprop ro.build.version.release` / `ro.build.version.sdk` / `ro.miui.ui.version.name`
2. 包名确认：`pm list packages | grep -iE "note|mms|sms"`
3. 便签 Provider：`dumpsys package com.miui.notes | grep -iE "authority|provider"`
4. 数据库：`su -c ls /data/user/0/com.miui.notes/databases/` → 复制到 `/sdcard/Download/` →
   `adb pull` 回本机分析表结构（待办表、字段、type 枚举）
5. 若手机有 sqlite3：直接 `su -c "sqlite3 ... .tables/.schema"` 查看

**安全承诺**：P0 阶段只执行上述只读命令；任何写入/修改操作（含后续便签写入实验）都会
先向用户明示命令与影响，获得确认后才执行。
