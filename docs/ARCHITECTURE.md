# 取件码助手 · 架构与技术说明

> **文档定位**：给「要接手这个项目继续开发的人」看的交接文档。
> 读者假设：你会 Java/Android，但**没有读过这个仓库的任何一行代码**。
>
> **基线版本**：`versionCode 310` / `versionName 3.1.0`（`app/build.gradle:13-14`，与 `app/AndroidManifest.xml:4-5` 一致）
> **源码根**：`app/src/io/github/okaidev/pickupcode/`（下文所有文件名不带路径时均指这里）
> **包名**：`io.github.okaidev.pickupcode`
> **构建**：AGP + `java.srcDirs = ['src']`（非标准布局，`app/build.gradle:47-54`）；Java 11，无 androidx 依赖；Xposed API 仅 `compileOnly`
>
> **行号快照时间：2026-10-04 约 02:50。** 本仓库当时正在被并行修改，本文中所有 `文件:行号` 引用都对应该快照，
> 后续提交后会漂移。**如果行号对不上，以文件内容为准，不要以行号为准。**
>
> **不确定的地方我一律写「待确认」，不猜。**

---

## 目录

1. [这个软件解决什么问题](#1-这个软件解决什么问题)
2. [核心设计：为什么这么复杂](#2-核心设计为什么这么复杂)
3. [跨进程配置共享机制](#3-跨进程配置共享机制)（**最难懂的一章，建议先读**）
4. [提取引擎](#4-提取引擎)
5. [写库](#5-写库)
6. [用户可见功能全清单](#6-用户可见功能全清单)
7. [代码文件职责地图](#7-代码文件职责地图)
8. [⚠️ 测试覆盖不到的地方](#8-️-测试覆盖不到的地方)（**最重要，请认真读**）
9. [已知问题与限制](#9-已知问题与限制)
10. [给维护者的注意事项](#10-给维护者的注意事项)

---

## 1. 这个软件解决什么问题

### 1.1 大白话

快递到了，驿站/丰巢/兔喜会给你发一条短信，里面藏着取件码，比如：

```
【菜鸟驿站】您的包裹已到站，凭4-6-7602到新郑龙湖富田兴龙湾31号楼店取件。
                                 ^^^^^^
```

这个码只在短信里。待你走到驿站门口掏手机翻短信找码，是一件很烦的事。

**这个模块做的事**：收到这类短信 → 自动把取件码抠出来 → 自动写成一条待办，落在你本来就会看的 App 里（小米笔记 / ColorOS 日历 / ColorOS 便签），并弹一条通知让你复制码。

它不做的事：不把你的短信内容传给别人；除了检查更新与规则热更外没有任何联网行为；不上传任何东西。

### 1.2 输入 → 输出

```
输入：手机收到的取件短信  +  快递 App（菜鸟/京东/淘宝…）发的取件通知
                    │
                    ▼
      ┌─────────────────────────────────────────────┐
      │ 取件码 = 4869-9777                            │
      │ 来源   = 南京百米需（取正文开头的【】括号品牌名）  │
      │ 地点   = 菜鸟驿站3号柜                         │
      │ 时间   = 2026-10-04 14:22                      │
      └─────────────────────────────────────────────┘
                    │
                    ▼
输出：待办 App 里一条置顶待办 + 一条可点击复制的通知
      📦 取件码 4869-9777｜南京百米需｜菜鸟驿站3号柜｜2026-10-04 14:22
```

### 1.3 用户必须先具备什么

**这不是「装上就能用」的 App，它是一个 LSPosed 模块。**下面每一项缺了都跑不起来。

| 前提 | 缺了会怎样 | 在哪检查 |
|---|---|---|
| **root**（Magisk / KernelSU） | 完全跑不起来。模块靠 `su -M` 起子进程跑 `sqlite3`，直接写目标 App 的私有数据库 | 体检第 2 项（`Diagnostics.checkRoot()`） |
| `su -M`（全局挂载命名空间）支持 | 能启动但打不开目标库，报 `unable to open database file` | README FAQ Q3 |
| **LSPosed**（Zygisk 或原版） | 完全跑不起来。模块靠 Xposed 注入到别的进程里 | 体检第 1 项（`Diagnostics.checkLsposedInject()`） |
| **LSPosed 作用域勾对** | 收不到短信，界面完全无提示 | 体检第 3 项（`Diagnostics.checkScope()` + `Repair.lsposedStatus()`） |
| 通知权限（Android 13+） | 待办照写，但**没有弹窗提醒** | 体检第 4 项 |
| `sqlite3` 部署到 `/data/local/tmp/pickup_sqlite/` | 写不了库 | 体检第 5 项，或点「一键部署」自动装 |

**LSPosed 作用域要求（最容易勾错的地方）**：

| 包名 | 是什么 | 必需性 |
|---|---|---|
| `android` | system_server。**S1 短信广播 + 通知 Hook 都在这里** | **必需**（在 LSPosed 列表底部、**不带推荐角标**，极易漏勾） |
| `com.android.phone` | 电话服务进程，S1 兜底 | 必需 |
| `com.android.mms` | 系统短信 App，S2–S7 多点冗余 | 必需 |
| `com.android.providers.telephony` | 短信数据库提供方，**S8 兜底主通道** | 事实上必需。`Repair.SCOPE_OPTIONAL` 把它标为「可选」（`Repair.java:45`，注释说实测部分 HyperOS 合并进 phone 进程），**但该常量目前无任何引用**（见 §9.3 #13） |
| `com.miui.notes` | 小米笔记（写入目标） | 仅小米后端时校验（`Repair.scopeRequired()`，`Repair.java:38-44`） |
| `com.coloros.note` | ColorOS 便签（写入目标） | 声明在 `arrays.xml`，但**不在 `scopeRequired()` 里** |

> ⚠️ `android` 和 `system` 是两回事。勾「系统框架（system）」**完全无效**——`XposedEntry.java:40-42` 虽然兼容 `system` 这个字符串，但 LSPosed 里真实存在的是 `android` 进程。
> 写入目标 App（笔记/日历）**不需要**勾作用域：模块是 root 直写它的库，不注入它。

---

## 2. 核心设计：为什么这么复杂

### 2.1 复杂度来源一句话

> **这个模块的绝大部分代码，跑在别人的进程里。**

`com.android.providers.telephony` 和 `android`(system_server) 都是系统进程，它们：

- 用的是**别的 UID**，读不到模块 App 的私有目录（`/data/user/0/io.github.okaidev.pickupcode/`）；
- 不能随便用模块身份弹通知（归属不对，会误导用户）；
- 会被 ROM 的后台冻结策略干掉（ColorOS 划掉后台 → 模块进程唤不醒）；
- 多线程并发（system_server 的通知回调），**任何跨线程共享的变量都不可靠**（`NotiHook.java:112-113` 的注释就是在讲这个）。

于是所有「看起来一句 `SharedPreferences.getInt()` 就能搞定」的地方，都被改写成
「root 写一个文件到 `/data/local/tmp`，对方直接 `FileReader` 读」。

### 2.2 四条链路全景图

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ ① 短信链路（主通道，默认开）                                                    │
│                                                                              │
│  [com.android.providers.telephony]                                           │
│    SmsProvider.insert / bulkInsert                                           │
│    MiuiTelephonyProviderImpl.insert / bulkInsert     ← 部分 HyperOS 的实现类  │
│      → SmsBridge.handleContentValues()            ← 整条短信一次入库，立即处理  │
│    ────────────────────────────────────────────                               │
│  [com.android.mms]                                                            │
│    SmsReceiver / HighPrivilegedSmsReceiver / BeidouSmsReceiver /              │
│    SmsReceiverService.onReceive / handleSmsMessage / handleSmsReceived       │
│      → SmsBridge.processArguments()               ← 多段，8s 窗口 / 最多 4 段   │
│    ────────────────────────────────────────────                               │
│  [com.android.phone | android | system]                                       │
│    SmsBroadcastReceiver.onReceive                (S1)                         │
│      → SmsBridge.processArguments()                                           │
│                            │                                                  │
│                            ▼                                                  │
│         ContentResolver.call(content://…pickupcode.provider, "onSms")          │
│                            │   ← 主通道：Provider 唤醒，不受冻结/冷启动限制      │
│                            │  ← 抛异常时退 sendBroadcast(ACTION_ON_SMS)       │
│                            ▼                                                  │
│         [io.github.okaidev.pickupcode]  模块 App 进程                         │
│              TodoProvider.call()  或  SmsEventReceiver.onReceive()             │
│                            │                                                  │
│                            ▼                                                  │
│              TodoWriter.handle() → 去重 → TodoWriter.writeTodo()              │
│                                    → su sqlite3 直写目标库                   │
│                                    → Notifier.notifyCodes() 弹通知            │
└──────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────────┐
│ ② 通知链路（可选，默认关）                                                      │
│                                                                              │
│  [android / system_server]                                                     │
│    NotificationManagerService.enqueueNotificationInternal  (afterHook)        │
│      → NotiHook.handleEnqueue()                                               │
│          包名白名单(6 个) → 读 /data/local/tmp/…/enable_noti_hook 开关          │
│          → 拼合 title + text + bigText                                        │
│          → PickupExtractor.extract("通知:包名", text)                          │
│              ├ 提取为空 → NotiRelay.relayMissed() ─┐                          │
│              └ 提取成功 → NotiRelay.relayHit()    ─┤                          │
│                                                     ▼                          │
│                    sendBroadcast(ACTION_ON_SMS, sender="通知:<包名>")           │
│                                                     │                          │
│                                                     ▼                          │
│              [模块 App 进程] SmsEventReceiver ──┘   ← 之后完全复用短信链路     │
│                                                                              │
│  ⚠️ 通知链路没有 SystemDirectWriter 直写兜底（NotiRelay.java:14-16 明说）        │
└──────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────────┐
│ ③ 冻结兜底直写（可选，默认关；ColorOS 下首启默认开）                            │
│                                                                              │
│  [com.android.providers.telephony]  ← 与 ① 同一个进程！                       │
│    SmsBridge.flush() 里 ContentResolver.call 抛异常                            │
│      → SystemDirectWriter.fallback(ctx, sender, body)                         │
│          读 /data/local/tmp/…/enable_sys_direct_write（进程内只探测一次）      │
│          → ProcessSync.load() 取配置快照（进程内只读一次）                    │
│          → 黑名单过滤 → lookLikePickupSms() → extract()                       │
│          → NotesBackend.propsBackend() 定后端（无 Context，只能按 ROM 属性猜） │
│          → existsSqlOf() 先查目标表是否已有同码未完成条目                      │
│          → su sqlite3 < 临时 .sql 文件                                         │
│      ⚠️ 不弹通知（冻结态下没有模块身份，弹了归属不对）                          │
│      ⚠️ 需要给 com.android.providers.telephony 授权 root                       │
└──────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────────┐
│ ④ 模块 App 侧处理（① ② 的唯一落点）                                             │
│                                                                              │
│  [io.github.okaidev.pickupcode]                                               │
│    两个冷启动入口（都必须在处理前初始化规则引擎）：                              │
│       TodoProvider.onCreate()        TodoProvider.java:28                    │
│       SmsEventReceiver.onReceive()   SmsEventReceiver.java:26                │
│            └── PickupExtractor.init(ctx)  ← v3.0.0 架构修复，见 §4.5          │
│    写库：TodoWriter.handle()                                                 │
│    写库 SQL：NotesBackend.insertSqlOf()                                        │
│    通知：Notifier.notifyCodes()                                                │
│    设置页：LauncherActivity（1840 行，纯代码建 UI，无 XML 布局）                 │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 2.3 四条链路的硬事实表

| # | 链路 | 运行进程（包名） | Hook 点 | 进程内类 | 到模块 App 的传输 | 默认 | 需重启 |
|---|---|---|---|---|---|---|---|
| ① | 短信 · S8 | `com.android.providers.telephony` | `SmsProvider.insert` / `bulkInsert`；`MiuiTelephonyProviderImpl.insert` / `bulkInsert`（`XposedEntry.java:92-115`） | `SmsBridge` | `ContentResolver.call(provider,"onSms")` → 失败退 `sendBroadcast` | **开** | 否 |
| ① | 短信 · S2–S7 | `com.android.mms` | `SmsReceiver.onReceive`、`HighPrivilegedSmsReceiver.onReceive`、`BeidouSmsReceiver.onReceive`、`SmsReceiverService.onReceive/handleSmsMessage/handleSmsReceived`（`XposedEntry.java:121-172`） | `SmsBridge` | 同上 | **开** | 否 |
| ① | 短信 · S1 | `com.android.phone` / `android` / `system` | `com.android.internal.telephony.SmsBroadcastReceiver.onReceive`（`XposedEntry.java:177-191`） | `SmsBridge` | 同上 | **开** | 否 |
| ② | 通知 | `android`（**仅当 `packageName=="android"` 且 `processName=="android"`**，`XposedEntry.java:46-49`） | `NotificationManagerService.enqueueNotificationInternal`（afterHook，`NotiHook.java:77-89`） | `NotiHook` + `NotiRelay` | **只有** `sendBroadcast(ACTION_ON_SMS)` | **关** | 建议 |
| ③ | 冻结兜底直写 | `com.android.providers.telephony` | 无新 Hook 点；由 ① 的 `SmsBridge.flush()` 异常分支调用（`SmsBridge.java:298-300`） | `SystemDirectWriter` | 不出本进程（直接 su 写库） | **关**（ColorOS 首启默认开） | **是** |
| ④ | 模块 App 侧 | `io.github.okaidev.pickupcode` | 无 Hook（`ContentProvider` + `BroadcastReceiver`，`AndroidManifest.xml:40-52`） | `TodoProvider` / `SmsEventReceiver` / `TodoWriter` | — | — | — |

> `com.android.providers.telephony` 既是 ① 又是 ③：**同一个进程里，Provider 唤醒成功走 ①，抛异常走 ③**。
> 两者共享 ③ 的「目标表已有同码则跳过」查询（`SystemDirectWriter.java:159-162`），所以不会双写。
>
> **关于「开关是否需要重启」**：
> - `enable_noti_hook` 每次 `enqueueNotificationInternal` 都读一次文件（`NotiHook.java:133-144`）→ **通知侧实际即时生效**；
>   但 `NotiHook.install()` 在挂 Hook 时才合并一次 `notiPkgs` 白名单（`NotiHook.java:62-70`）→ **用户追加包名需重启**。设置页的文案统一写「重启手机后生效」，是保守表述。
> - `enable_sys_direct_write` 被 `enabledCache` 缓存，每进程只探测一次（`SystemDirectWriter.java:46,80-88`）→ **必须重启**。

### 2.4 数据传递与去重

**跨进程传递**

| 通道 | 载体 | 载荷 | 备注 |
|---|---|---|---|
| 短信 → App（主） | `Bundle` via `ContentResolver.call` | `sender` / `body` / `ts` | Provider 调用会真正启动被冻结的进程 |
| 短信 → App（兜底） | `Intent` 广播 `io.github.okaidev.pickupcode.action.ON_SMS` | 同上 | `sendBroadcast` **不保证**接收方被唤醒 |
| 通知 → App | **同一个** `Intent` 广播 | 同上 + `miss=1`（错题本标记） | `sender = "通知:" + 包名`（`NotiRelay.java:45`） |
| App → 系统进程 | `/data/local/tmp/pickup_sqlite/*` 文件 | 见 §3 | `chmod 644`，系统进程免 su 直接读 |

**去重（从外到内，共 5 层）**

| 层 | 位置 | 指纹算法 | 容量 | 作用范围 |
|---|---|---|---|---|
| 1 | `SmsBridge.RECENT`（`SmsBridge.java:27-28`，`RECENT_MAX = 200`） | `sender + "\|" + body.hashCode()` | 200，LRU | 同一进程内多个 Hook 点重复触发 |
| 2 | `SmsBridge.PENDING`（`SmsBridge.java:31-33`） | key = sender | 8 秒 / 最多 4 段 | 多段短信**拼接**（不算去重，算聚合） |
| 3 | `SystemDirectWriter` 10s 节流（`SystemDirectWriter.java:53-55,143-146`） | 同第 1 层 | 单进程只记最后一个指纹 | 兜底通道并发刷写 |
| 4 | `TodoWriter` 的 `seen` 集合（`TodoWriter.java:76,89-95,117-124`） | **`code + "\|" + place`** | `DEDUP_MAX = 500`，LRU | **App 侧真正的写入门禁**（①② 都过这里） |
| 5 | `NotesBackend.existsSqlOf()`（`SystemDirectWriter.java:161-164,180-183`） | SQL `LIKE '%code%'` + 未完成 | — | 进程外：目标表里有没有同码未完成条目 |

第 4 层命中时**不写库**，`wrote` 返回空。为了让 UI 分得清「去重拦下」和「写失败」，`TodoWriter.lastDedupHits`（`TodoWriter.java:45-47,114`）单独记录，`ChainTestActivity` 读它来给用户正确反馈（`ChainTestActivity.java:365-372`）。

---

## 3. 跨进程配置共享机制

> **这是本项目最难懂、也最脆弱的部分。**改任何涉及它的代码，请先读 §8。

### 3.1 为什么不能用 SharedPreferences

因为**系统进程不是模块 App 进程**：

```
模块 App 的偏好文件：
  /data/user/0/io.github.okaidev.pickupcode/shared_prefs/dedup.xml
  属主 UID = 模块 App 的 UID，权限 0700

system_server（android 进程）           →  UID 1000，读不到
com.android.providers.telephony 进程   →  UID 1001（RIL）或其私有 UID，读不到
```

这不是「配置得对不对」的问题，是 **Linux 文件权限的硬边界**。代码里对此有多处明写：

- `ProcessSync.java:20-23`：「系统进程读不到模块 App 的 SharedPreferences，所以它们此前只能用『编译内置默认值』跑」
- `UserRules.java:47-51`、`NotiHook.java:28-29`、`SystemDirectWriter.java:21-24`

### 3.2 现在的方案：root 写 `/data/local/tmp` + chmod 644

```
┌──────────────── 模块 App 进程（有 root） ────────────────┐
│                                                       │
│  1. buildJson()  组装配置 JSON                          │
│  2. Base64.encodeToString(json, NO_WRAP)   ★见 §3.6    │
│  3. su -M -c "mkdir -p /data/local/tmp/pickup_sqlite;  │
│                cat > …/app_state.json << 'PICKUP_EOF'  │
│                <base64>                                │
│                PICKUP_EOF                              │
│                chmod 644 …/app_state.json"              │
│                          │                             │
└──────────────────────────┼─────────────────────────────┘
                           ▼
   /data/local/tmp/pickup_sqlite/app_state.json  (0644)
        ┌──────────────┴──────────────┐
        ▼                             ▼
┌───────────────────────┐   ┌──────────────────────────────┐
│ [system_server]       │   │ [providers.telephony]        │
│ NotiHook.install()    │   │ SystemDirectWriter.fallback()│
│   ProcessSync.load()  │   │   loadStateOnce()            │
│   → 内存 Rule 对象    │   │   ProcessSync.load()（一次） │
└───────────────────────┘   └──────────────────────────────┘
      普通 FileReader，**不需要 su**
```

约定目录 `/data/local/tmp/pickup_sqlite/` 定义在 `Repair.TARGET_DIR`（`Repair.java:24`）。
`ProcessSync.java:41-42`、`UserRules.java:63-64`、`SystemDirectWriter.java:37-38`、`TodoWriter.java:29-30`
**各自重复声明了同一个字符串**（有意解耦，但**改路径要改 4–5 处**，见 §9.3 #16）。

| 文件 | 内容 | 权限 |
|---|---|---|
| `app_state.json` | 官方规则集 + 黑名单 + 模板模式 + 自定义模板 + 通知白名单（**Base64**） | 644 |
| `user_rules.json` | 用户自定义规则数组（**Base64**，**不含测试样本**） | 644 |
| `enable_noti_hook` | 明文 `1` / `0` | 644 |
| `enable_sys_direct_write` | 明文 `1` / `0` | 644 |
| `sqlite3` + `lib/*.so` | 部署好的 sqlite3 二进制与依赖（`Repair.java:25-26`） | 755 / 644 |

> `Repair.deploySqlite3()`（`Repair.java:53-95`）负责部署：APK assets → App 私有目录 → `su cp` 到约定目录 → chmod → 验证 `--version`。
> App 内置了一份 arm64 的 sqlite3 3.53.4 及 7 个依赖（`app/assets/sqlite3/`，约 3.7 MB），用户**不需要 adb / Termux**。

### 3.3 配置快照 `app_state.json` 的字段

定义在 `ProcessSync.State`（`ProcessSync.java:49-55`），写入在 `buildJson()`（`ProcessSync.java:118-145`），读取在 `load()`（`ProcessSync.java:184-210`）。

| JSON 键 | 类型 | 来源 | 空值含义 | 谁在用 |
|---|---|---|---|---|
| `rulesJson` | string（**嵌套** JSON 字符串） | **反射读 `PickupExtractor.activeRules` 私有静态字段**，再调 `toJson()`（`ProcessSync.java:122-134`，`getDeclaredField` 在 `:124`） | `""` = 用编译内置 `ExtractorRules.createDefault()` | `NotiHook.install()` → `PickupExtractor.initForSystemProcess()`；`SystemDirectWriter.fallback()` 同 |
| `blacklist` | string（逗号分隔） | `SharedPreferences "dedup"` → `blacklist` | `""` = 用 `SystemDirectWriter.DEFAULT_BLACKLIST`（8 词，`:49-52`） | `SystemDirectWriter.fallback()` 逐词 `body.contains()` |
| `todoMode` | int | `SharedPreferences "dedup"` → `todo_mode` | 默认 `1`（`MODE_FULL`） | `SystemDirectWriter.fallback()` → `TodoWriter.buildContent()` |
| `customTpl` | string | `SharedPreferences "dedup"` → `todo_custom` | `""` = 用默认模板 | 同上 |
| `notiPkgs` | string（逗号分隔） | `NotiHook.BUILTIN_PKGS`（6 个）+ 用户的 `noti_extra_pkgs`（`ProcessSync.notiPkgsOf()`，`:148-167`） | `""` = 只用内置 6 个 | `NotiHook.install()` 合并进运行时白名单（`NotiHook.java:62-70`） |

**⚠️ 快照里没有 `backend`（写入目标）字段。** 见 §9.2 #6。

**谁写、什么时候写**

| 触发时机 | 调用点 |
|---|---|
| 主界面冷启动（**节流 30 分钟**） | `LauncherActivity.onCreate()` → `ProcessSync.pushThrottled()`（`LauncherActivity.java:88`，节流逻辑 `ProcessSync.java:59-84`，`PUSH_THROTTLE_MS` 在 `:60`） |
| 保存自定义模板 | `LauncherActivity.java:517` |
| 切换模板模式 | `LauncherActivity.java:1609` |
| 保存黑名单 | `LauncherActivity.java:698` |
| 规则集热更新成功 | `UpdateCenter.fetchRules()`（`UpdateCenter.java:206`） |
| 设置通知来源包名 | `ProcessSync.setExtraNotiPkgs()`（`ProcessSync.java:173`） |

> `pushThrottled` 存在的理由写在 `ProcessSync.java:66-70`：快照此前只在「热更成功」或「用户改设置」时写，
> **全新安装且从未改过任何设置的用户，系统进程永远读不到快照**，等于该修复对他们无效。所以冷启动补推一次；30 分钟节流避免每次开 App 都跑 su。

**谁读、什么时候读**

| 进程 | 时机 | 频率 |
|---|---|---|
| `android` (system_server) | `NotiHook.install()`，即进程启动挂 Hook 时 | **一次** |
| `com.android.providers.telephony` | `SystemDirectWriter.fallback()` → `loadStateOnce()`（`SystemDirectWriter.java:66-76`） | **每进程一次**（`SystemDirectWriter.java:57-62` 注释：每条短信都读文件 + 重跑 21 条冒烟会明显拖慢） |

进程重启即失效并重读，所以**不会读到陈旧配置**；代价是改配置后必须重启相关进程。

### 3.4 两个开关标志文件

两者结构完全一样：一个明文文件，内容 `1`（开）或 `0`（关），`chmod 644`。

| | `enable_noti_hook` | `enable_sys_direct_write` |
|---|---|---|
| 控制什么 | 通知提取链路（②） | 冻结兜底直写（③） |
| 写入方 | `LauncherActivity.toggleNotiHookFlag()`（`LauncherActivity.java:1032-1042`） | `LauncherActivity.toggleSysDirectWrite()`（`:988-1014`）；ColorOS 首启自动写 `1`（`:1069-1074`） |
| 读取方 | `NotiHook.isFlagOn()`（`NotiHook.java:133-144`），`FLAG_FILE` 定义在 `:34` | `SystemDirectWriter.readFlag()`（`:90-99`），`FLAG_TMP` 在 `:41` |
| 设置页读状态 | `LauncherActivity.isNotiHookFlagOn()`（`:1017-1028`），**直接 `FileReader` 读文件**，不读 SharedPreferences | 读 SharedPreferences `sys_direct_write` |
| App 侧偏好键 | `noti_hook`（只作记录，**不参与判定**） | `sys_direct_write` |
| 默认值 | 关 | 关；**ColorOS 环境首启被强制改成开**（`LauncherActivity.updateSdwDisplay()`，`:1064-1074`）；小米环境直接隐藏整栏并强制写 `false`（`:1053-1058`） |
| 生效时机 | 每次通知入队都读文件 → 实际即时 | `enabledCache` 每进程只探测一次 → **必须重启** |
| 读取要不要 su | 否（644 够了） | **是**（`runShortCmd(ctx,"cat …")`，`:243`）；`SystemDirectWriter.java:48` 注释承认这是为了省掉 su 而多一条命令链 |
| APK assets 兜底 | 无 | `FLAG_ASSET`（`:43`）声称可回落，但 `readFlag()` 第 2 步**直接 `return false`**（`:97-98`）——**回落逻辑未实现，待确认** |

> **设计要点**：通知开关的状态展示必须以**读文件**为准，不能以 SharedPreferences 为准，否则会出现「界面显示开了、系统进程没开」。

### 3.5 用户规则同步（`user_rules.json`）

机制与上面同构，多两件事：

1. **同步前剥掉测试样本**（`UserRules.copyWithoutSamples()`，`UserRules.java:760-782`）。
   测试样本是用户粘贴的**真实短信原文**（含姓名/手机号/住址），而同步文件是 `/data/local/tmp` 下 **644 全局可读**的。系统进程只用规则做匹配、从不读 `testCases`，剥掉不影响任何功能。
2. **读取端做「先新后旧」双格式兼容**（`UserRules.loadForSystemProcess()`，`:301-329`，判断在 `:308-319`）：文件首字符不是 `{`/`[` 就按 Base64 解码；解码失败再按明文 JSON 试一次。防止升级后老文件读不出来。

### 3.6 ⚠️ 必须知道的坑：shell 会吃掉你的双引号

**这是本项目真实发生过的事故。写在这里的人请务必读完。**

#### 现象

`Diagnostics.suExec()` 第 369 行：

```java
private static String[] suExec(String suBin, String cmd, int timeoutSec) {
    Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c",
            suBin + " -M -c \"" + cmd.replace("\"", "'") + "\""});
    //                          ^^^^^^^^^^^^^^^^^^^^^^^^
    //                 命令行里的每一个双引号都被替换成单引号
```

也就是说：**你传给 `suExecPublic` 的命令字符串里，所有 `"` 都会变成 `'`。**

#### 后果

`app_state.json` 里存的是 `rulesJson` —— 一份**嵌套 JSON**，里面全是 `\"`、`\d`、`\s`、`\/` 这类转义。
直接内联进 shell 命令，结果被静默篡改成 `{'rulesJson':'{\'version\'...` 的坏数据。

**注意「静默」两个字**：不抛异常、不崩溃、写文件这一步还成功了（`chmod` 也执行了）。
后果只是——通知通道读不到规则，悄悄回落到编译内置规则。「修复等于没生效」，界面完全看不出异常。

同样的事故在 `user_rules.json` 上也发生过：`UserRules.java:285-287` 记着具体例子——
用户正则 `取件码["']?(\d+)` 里的双引号到了系统进程变成单引号，正则语义变了，**不报错、不崩溃，只是安静地提不出码**。

#### 现在的规矩

> **凡是写文件的内容，必须先做 Base64 编码。**

Base64 字符集只有 `A-Za-z0-9+/=`，不含引号、不含反斜杠、不含空格，可以安全穿过整条命令链。

**正确写法**（`ProcessSync.java:106-110`，`UserRules.java:289-293` 两处同构）：

```java
String b64 = android.util.Base64.encodeToString(
        json.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);   // NO_WRAP 必须
Diagnostics.suExecPublic("mkdir -p " + SYNC_DIR + "; "
        + "cat > " + SYNC_FILE + " << 'PICKUP_EOF'\n" + b64 + "\nPICKUP_EOF\n"
        + "chmod 644 " + SYNC_FILE, 20);
```

**错误写法**（禁止）：

```java
Diagnostics.suExecPublic("cat > " + SYNC_FILE + " << 'EOF'\n" + json + "\nEOF", 20);
```

**新增跨进程文件时 checklist**：

- [ ] 写入端是否 Base64（`NO_WRAP`）？
- [ ] 读取端是否有「先 Base64 后明文」的双格式兼容（参考 `UserRules.loadForSystemProcess()`）？
- [ ] 内容里有没有双引号 / 反斜杠 / `$` / 反引号？
- [ ] 有没有在真机上 `cat` 过这个中间文件确认内容正确？

> 📌 出处：`ProcessSync.java:96-105`、`UserRules.java:283-287`、`CHANGELOG.md:33-34`。

---

## 4. 提取引擎

### 4.1 优先级：谁说了算

```
用户自定义规则（UserRules）                ← 最高，用户写了就是要覆盖默认
        │ 没命中
        ▼
官方 rules.json（热更 / assets / 本地缓存）   ← 运行时 atomic 替换（volatile 字段）
        │ 加载失败 / 冒烟不过
        ▼
编译内置 ExtractorRules.createDefault()      ← 版本 5
```

实现见 `PickupExtractor.extractWithRules()` 开头（`PickupExtractor.java:411-420`）：
用户规则命中即返回，**不再走四层判定**。

> **v3.0.0 关键修复**：前置门禁 `hasFeatureWords()` / `lookLikePickupSms()` 在用户规则命中时也会放行（`PickupExtractor.java:277,298`）。
> 否则像学校自提柜的「物品编号」、物业的「存取码」这类官方词表根本没覆盖的说法，会在进引擎前就被短路掉，用户规则形同虚设。

### 4.2 四层判定

```
                        extractWithRules(r, sender, body)
                                     │
  ┌──────────────────────────────────┴──────────────────────────────────┐
  │ Tier0  特征词门禁                                                     │
  │   patternFeature 必须 find()，否则直接返回空                          │
  │   正则：featureWords|extraFeatureWords 的 or                          │
  │   例外：用户规则命中则跳过本层                                         │
  └──────────────────────────────────┬──────────────────────────────────┘
                                     │
              ┌──────────────────────┴──────────────────────┐
              │ TierA  强锚点 keywordAnchors                │
              │   patternKeyword（ExtractorRules.compile）  │
              │   例：取件码/取货码/提货码/取件号/凭取件码…     │
              │   命中后按 [,，、;；\s]+ 拆成多码簇            │
              │   ★不做 negativeKeywords 一票否决（最高置信）  │
              │   ★v3.1.0：走 addIfValidSemantic，每个 token   │
              │     也要过一遍 isExcludedToken                 │
              └──────────────────────┬──────────────────────┘
                                     │ result 仍为空才走下一层的「专属分支」
              ┌──────────────────────┴──────────────────────┐
              │ TierB  凭/出示 + 码                        │
              │   patternBy = byWords + 码形 + byLookahead   │
              │   例：凭552577至同兴冠寓…取件                 │
              │   ★v3.1.0：同样走 addIfValidSemantic         │
              └──────────────────────┬──────────────────────┘
                                     │ 仅当 A+B 都无结果
              ┌──────────────────────┴──────────────────────┐
              │ TierC  夹取型 码…到/至…取                   │
              │   patternCodeToTake，地点+动作双确认         │
              └──────────────────────┬──────────────────────┘
                                     │ 仅当 A+B+C 都无结果
              ┌──────────────────────┴──────────────────────┐
              │ TierD  评分兜底（严格）                       │
              │   ① patternNegative 命中 → 整体否决（返回空） │
              │      ★仅在无强锚点时生效                     │
              │   ② 逐 token：validCode() → isExcludedToken() │
              │   ③ 取 token 前后各 24 字窗口，评分：         │
              │        窗口含特征词 +2                      │
              │        窗口含动作词 +2                      │
              │        横线簇形状    +1                      │
              │        纯数字 6–9 位 +1                      │
              │      score >= 4 才收                        │
              └─────────────────────────────────────────────┘
```

源码位置：`PickupExtractor.java:422-482`。

各层前置条件与优先级：

| 层 | 前置条件 | 后续层是否执行 | 置信度 |
|---|---|---|---|
| 用户规则 | `UserRules.tryMatch` 命中发送方 + 正则有提取 | **不再执行 Tier0–D** | 用户自证（必须自测通过） |
| Tier0 | 无（用户规则命中则跳过） | 命中才继续 | 门禁 |
| TierA | 无条件执行 | **无条件继续执行 B/C/D**（它们往同一个 `result` 里加码） | 最高 |
| TierB | 无条件执行 | 同上 | 中 |
| TierC | 仅当 A+B 都没结果 | 继续 D | 中 |
| TierD | 仅当 A+B+C 都没结果 | — | 最低（严格评分 + 负向否决） |

结果集是 `LinkedHashSet`（`PickupExtractor.java:408`），**保持出现顺序**并自然去重。

### 4.3 码形与语义排除

**码形合法性** `validCode()`（`PickupExtractor.java:538-543`），二选一：

| 规则字段 | 正则 | 含义 |
|---|---|---|
| `dashPattern` | `(?:[A-Za-z]-\d{1,6}\|[A-Za-z]?\d{1,6})(?:-[A-Za-z]?\d{1,6}){1,3}` | **横线簇，必须含至少一个横线**。杜绝裸数字被当成码 |
| `alnumPattern` | `[A-Za-z]?\d{3,9}` | 可选字母 + 3–9 位数字。token 总长 > 20 直接否（`:539`） |

**语义排除** `isExcludedToken()`（`PickupExtractor.java:540-…`），7 条规则：

| # | 排除对象 | 依据 | 例 | 生效层 |
|---|---|---|---|---|
| 1 | URL 区间内的数字 | 硬编码 `patternUrl`（`https?://\S+`） | `http://if.189.cn` 里的 189 | A/B/C/D |
| 2 | 掩码手机号 ±2 字范围内 | 硬编码 `patternMaskedPhone` | `159****6739` 附近的 159 / 6739 | A/B/C/D |
| 3 | 后接量词/单位/时间（看后 4 字） | `excludeTailWords` | `132号`、`284号格口`、`0.50元`、`6折` | A/B/C/D |
| 4 | 前接修饰语/单号类（看前 6 字） | `excludeHeadWords` | `已超6`、`低至6折`、`单号123123123`、`订单1064` | A/B/C/D |
| 5 | 后接 `:` | 硬编码 | `23:59` | A/B/C/D |
| 6 | 完整 11 位手机号 | 硬编码 `P_PHONE` | `13800138000` | A/B/C/D |
| 7 | 前接 `*` | 硬编码 | 顺丰`顺丰*72778` | A/B/C/D |

> 第 1/2/5/6/7 条**硬编码在 Java 里**，`rules.json` 改不了，热更只能扩第 3/4 两条词表。

> **v3.1.0 重要修复**（`PickupExtractor.java:428-445,484-533`）：此前 **TierA / TierB 只做 `validCode`，完全绕过 `isExcludedToken`**。
> 后果是所有语义排除规则对「最自信的那一类短信」恰好完全失效——而锚点短信正是用户投诉「抓错码」的主要来源。
> 实测反例（修复前 TierA 会误提）：
> ```
> 「…3号柜284号格口，取件码284号」   → 误取 284（其实是格口号）
> 「…取件码为123123123」            → 误取 123123123（其实是订单号）
> 「…取件码为159****6739」          → 误取 159（掩码手机号前半段）
> ```
> 修复方式：TierA/B 改走带原文位置的 `addCluster(...)` / `addIfValidSemantic(...)`。
> **定位失败时保守放行**（`:497-499`）——原文大小写/空白与捕获组不一致时退回旧的「只查形状」行为，避免修复过头反而漏抓。

**地点提取**（`PickupExtractor.extractPlace()`，`PickupExtractor.java:326-366`）：

| 顺序 | 策略 | 例 |
|---|---|---|
| 0（最高） | 用户规则指定地点（`tryMatch()[2]`） | 固定文本或 `$1` |
| 1 | 动词夹取（`patternPlace`：`到/至…取/领取/取件/取货/，。,`） | `苍山下坡圆通快递` |
| 2 | 前缀+后缀词表（`patternPlaceFallback`） | — |
| 3 | 码簇尾段：最后一个合法码之后的文本，含 `placeSuffixes` 之一、长度 2–40、且不含营销噪声（`:369-382`） | `中峰乡信用社斜对面圆通速递妈妈驿站` |
| 兜底 | 返回 `"—"` | — |

### 4.4 规则加载顺序与冒烟门禁

`PickupExtractor.init(ctx)`（`PickupExtractor.java:70-120`）：

```
1. loadUserRulesInternal(ctx)          ← 用户规则永远先加载，与官方规则成败无关
2. files/rules_active.json 存在？
      ├ 是 → fromJson → runSmokeTest(candidate)
      │        ├ 通过 → 再读 assets 做【版本比较】
      │        │        assets.version > 本地缓存.version ? 用 assets 并回写缓存
      │        │        :                                    用本地缓存
      │        │        （v3.1.0 P2：修复「升级 APK 后新包自带更高版本规则，
      │        │          却被旧本地缓存压住」——离线用户永远读不到新规则）
      │        └ 不通过 → 删除缓存文件，继续
      └ 否
3. assets/rules/rules.json → fromJson → runSmokeTest → 通过则写入缓存
4. 以上都不行 → ExtractorRules.createDefault()（编译内置 v5）
```

**冒烟门禁** `runSmokeTest()`（`PickupExtractor.java:240-259`）：**21 条黄金用例**（`SMOKE_TEST_CASES`，`:44-67`，其中 6 条是反例）。
正例要求**提取结果完全相等**（顺序、数量都算），反例（期望 `-`）要求**提取为空**。任何一条不通过 → **一票否决**。

同一个门禁用在四处：

| 场景 | 位置 |
|---|---|
| 本地缓存加载 | `PickupExtractor.java:83` |
| assets 加载 | `PickupExtractor.java:108` |
| 热更新应用 `applyNewRules()` | `PickupExtractor.java:206` |
| 系统进程同步快照 `initForSystemProcess()` | `PickupExtractor.java:165` |

### 4.5 冷启动必须初始化规则引擎（v3.0.0 架构修复）

否则用户没手动打开过 App 时，真实短信链路跑的是编译内置旧规则（`CHANGELOG.md:103-108`）：

| 进程入口 | 调用点 |
|---|---|
| `TodoProvider.onCreate()` | `TodoProvider.java:28` |
| `SmsEventReceiver.onReceive()` | `SmsEventReceiver.java:26` |
| `NotiHook.install()` | `NotiHook.java:59-61`（走 `initForSystemProcess`） |
| `SystemDirectWriter.fallback()` | `SystemDirectWriter.java:107-118` |
| `LauncherActivity.onCreate()` | `LauncherActivity.java:82` |
| `ChainTestActivity.onCreate()` | `ChainTestActivity.java:69` |
| `UserRulesActivity` | 无直接调用（只读版本号）；但它由 Launcher 启动，Launcher 已初始化 |

### 4.6 `rules.json` 字段全表

| 字段 | 类型 | 谁在用 | 改了会怎样 | 注意事项 |
|---|---|---|---|---|
| `version` | int | 热更判断（`UpdateCenter.java:204`）、诊断报告、UI 显示 | 需**同时**改 `createDefault()` | 只能递增 |
| `minAppVersionCode` | int | 热更兼容性闸门（`UpdateCenter.java:203`） | 同上 | 更高的 code 才允许装本 App |
| `description` | string | 仅展示 | 无 | — |
| `featureWords` | string[] | `patternFeature` → **Tier0 门禁** | 直接决定这条短信是否进入引擎 | 加词 = 放宽入口，误报面变大 |
| `extraFeatureWords` | string[] | 同上（`compile()` 拼在 featureWords 之后） | 同上，但**不替换**内置词表 | 热更扩充新驿站品牌用这个 |
| `actionWords` | string[] | `patternAction` → **TierD 评分 +2** | 只影响兜底层 | — |
| `keywordAnchors` | string[] | `patternKeyword` → **TierA** | 直接决定最高置信的提取路径 | **三副本一致性脚本重点校验项** |
| `byWords` | string[] | `patternBy` → **TierB** | 决定「凭/出示」是否触发 | — |
| `byLookaheadWords` | string[] | `patternBy` 的前瞻字符集 | 决定「凭X之后有引导字」才算命中 | 空则回落硬编码 `到去取领取票至` |
| `dashPattern` | string（正则） | `patternDashShape` → 码形校验 + TierC | 放宽 = 裸数字被误当码 | 必须**含至少一个横线** |
| `alnumPattern` | string（正则） | `patternAlnumShape`（加 `^...$` 锚） | 放宽 = 大量数字被误当码 | — |
| `placePrefixes` | string[] | `patternPlaceFallback`（策略 2） | 只影响地点回退 | 项内可直接写正则片段（例：`已到站[，,包到至\s]*`） |
| `placeSuffixes` | string[] | 同上 + `looksLikePlace()`（策略 3） | 影响地点回退与码簇尾段 | 同上 |
| `negativeKeywords` | string[] | `patternNegative` → TierD 一票否决 | **仅在无强锚点时生效**，加词不会误杀真取件短信 | 置空则 `patternNegative = null` |
| `excludeTailWords` | string[] | `isExcludedToken()` 第 3 条 | 数字后紧跟这些字 → 不是码 | 缺失时回落内置默认（`ExtractorRules.fromJson`） |
| `excludeHeadWords` | string[] | `isExcludedToken()` 第 4 条 | 数字前紧跟这些词 → 不是码 | 同上 |
| — | — | `patternUrl` / `patternMaskedPhone` / `patternCodeToTake` / `patternPlace` | **硬编码在 `compile()` 里，不在 JSON** | 改要改 Java |

### 4.7 ⚠️ 规则文件有三份副本

```
        rules/rules.json                  ← 远端热更源（GitHub raw / jsdelivr）
                │  UpdateCenter.fetchRules() 从这里拉
                │
        app/assets/rules/rules.json       ← APK 内置兜底（离线用户靠它）
                │
                │  PickupExtractor.init() 读取
                ▼
    files/rules_active.json               ← 运行时缓存（App 私有目录）
    ════════════════════════════════════════════════════════
        app/src/.../ExtractorRules.java
          createDefault()                 ← ★编译进代码★
                                            system_server / providers.telephony
                                            没有 Context，读不到前两份，只能用这份
    ════════════════════════════════════════════════════════
```

**第三份最隐蔽**：漏改它时，**短信通道是好的、界面完全看不出异常**，只有通知提取会悄悄跑旧规则继续漏抓
（`test/check_rules_consistency.py:16-18`、`test/README.md:24-27` 都点名了这件事）。

**改规则后必须做两件事**：

1. 三处改齐（`version`、`minAppVersionCode`、`keywordAnchors` 至少要对齐）
2. 跑校验脚本：

```bash
python test/check_rules_consistency.py     # 退出码 0 = 一致，1 = 有问题
```

脚本（`test/check_rules_consistency.py`）检查五项：

| # | 检查 | 说明 |
|---|---|---|
| 1 | 文件存在性 | 三份都在 |
| 2 | **字节级一致** | `rules/rules.json` vs `app/assets/rules/rules.json`（sha 前缀对比） |
| 3 | 隐藏字符 | BOM / 零宽空格 U+200B / NBSP / 全角空格（按 UTF-8 字节序列检测，不靠引号配对——`check_rules_consistency.py:54-64` 有说明） |
| 4 | 内容一致 | `version`、`minAppVersionCode`、`keywordAnchors` 三处对齐（**含顺序**） |
| 5 | 结论 | 逐条打印 `[FAIL]`，返回 1 |

> 当前仓库三份状态：均为 `version 5` / `minAppVersionCode 293` / 13 个 `keywordAnchors`，一致。

---

## 5. 写库

### 5.1 三个后端

集中定义在 `NotesBackend`（`NotesBackend.java`）。选择优先级（`resolve()`，`:69-80`）：

```
1. 用户手动指定（SharedPreferences "dedup" → backend_override）  ← 优先级最高
2. 自动识别：装了 com.miui.notes 或是 MIUI/HyperOS ROM → xiaomi；否则 coloros_todo
```

**系统进程无 Context 时**只能用 `propsBackend()`（`:86-88`）：读系统属性 `ro.miui.ui.version.name` / `ro.mi.os.version.name`，非空 → `xiaomi`，否则 `coloros_todo`。

| 后端 | 目标 App | 数据库路径 | 表 | 置顶方式 | 完成方式 |
|---|---|---|---|---|---|
| `xiaomi` | 小米笔记 `com.miui.notes` | `/data/user/0/com.miui.notes/databases/todo.db` | `todo` | `custom_sort_id = MAX(custom_sort_id) + 1048576` | `is_finish=1, mark_finish_time=now` |
| `coloros_todo` | ColorOS 日历 | `/data/user/0/com.android.providers.calendar/databases/tasks.db` | `Tasks` | `sort_time = now` | `finish_time = now`（`completed` 列不用） |
| `coloros_note` | ColorOS 便签 `com.coloros.note` | `/data/user/0/com.coloros.note/databases/nearme_note.db` | `rich_notes` | `top_time = now` | 文本追加 `（✅ 已取件）`、`top_time=0` |

> ColorOS 16 把待办整体迁到了日历，便签 `todo` 表是遗留空表不可用（`NotesBackend.java:14-16`）。
> 官方任务接口 `content://com.oplus.task` 需平台签名权限，第三方调不了，所以只能 root 直写。

### 5.2 列值一致性

三条 insert SQL 都在 `NotesBackend.insertSqlOf()`（`:139-179`）。一致性靠三点保证：

1. **时间戳统一**：`now = (strftime('%s','now')*1000)`，`create_time` / `update_time` / `sort_time` / `timestamp` 全部用它（`:141`）
2. **文本统一**：`content` 与 `plain_text`（小米）/ `content`（ColorOS 日历）/ `text` + `html_text` + `summary_title` + `summary_content`（ColorOS 便签）写入**同一个**转义后的字符串
3. **转义统一**：只做单引号转义 + 去 NUL（`TodoWriter.sqlEscape()`）

其它固定列值：

| 后端 | 关键常量 |
|---|---|
| xiaomi | `is_finish=0, list_type=0, type=0, category=0, folder_id=0, source=0, input_type=0, remind_type=0, priority=0, hide_type=0, sort_id=0, version=1, local_status=0, server_status=0, words_count=0` |
| coloros_todo | `allDay=1, star=0, mutators='com.coloros.calendar', create_package='com.coloros.calendar_10325_7e18…', color=2, timezone='UTC', sys_version=1, force_reminder=0, canPartiallyUpdate=0, deleted=0, dirty=1` |
| coloros_note | `folder_id='00000000_0000_0000_0000_000000000000', skin_id='color_skin_white', extra='{"encryptStatus":-1,…}', version=0, is_local=1, recycle/alarm 全 0` |
| 三者通用 | `local_id` / `global_id` / `local_global_id` 用 SQLite 表达式 `UUID_SQL`（`:235-239`）内联生成 UUID v4 |

`coloros_todo` 的 `create_package` 常量取自日历 App 亲手创建的样本（`NotesBackend.java:164,171` 注释 + `docs/17-coloros-adaptation.md`）。
**升级 ColorOS 时这个值可能会变** —— 升级后如果待办写不进去，先查这里。

**小米后端的自愈 chmod**（`NotesBackend.chmodCmdOf()`，`:125-132`）：每次写入前 `chmod 711` App 目录 / `771` databases / `666` todo.db，补偿 App 的 su 子进程缺 DAC 特权。ColorOS 后端返回空串（root 直写不需要）。

### 5.3 写库执行链

```
TodoWriter.writeTodo(ctx, content, title)            ← synchronized
  │
  ├ NotesBackend.insertSql(ctx, content, title)
  │    └ detect(ctx) → 后端 → insertSqlOf()
  │
  ├ sql = ".timeout 5000\n" + insertSql
  │
  ├ 写入 App 私有目录 files/todo_sql.sql              ← 不经命令行，规避引号问题
  │
  └ TodoWriter.runSqlForOutput(ctx, sql)              (:170)
       └ for suBin in { su, /product/bin/su, /system/bin/su, /sbin/su, /su/bin/su }   (:178)
            Runtime.exec("sh","-c", suBin + " -M -c \"id; " + chmodCmd + "LD_LIBRARY_PATH=… sqlite3 <db> < todo_sql.sql\"")
            → exit 0 → 成功，记 lastWriteDiag
            → 否则记 trace，继续下一个
```

- 五个 su 路径的原因：HyperOS 的 Magisk su 在 `/product/bin/su`，应用 PATH 不含该目录（`TodoWriter.java:162` 注释）
- **SQL 一律走临时文件，不内联命令行**（`Repair.java:125` 注释：「SQL 经临时文件下发，规避多层 shell 引号转义」）
- 失败原因存 `lastWriteDiag`（`TodoWriter.java:35-38`），诊断报告和链路测试都读它

### 5.4 去重指纹

| 层 | 指纹 | 代码 |
|---|---|---|
| Hook 进程内 | `sender + "\|" + body.hashCode()` | `SmsBridge.java:243` |
| Hook 兜底通道 10s 节流 | 同上 | `SystemDirectWriter.java:141` |
| **App 侧写入门禁** | **`code + "\|" + place`** | `TodoWriter.java:89` |
| 兜底通道查目标表 | SQL `LIKE '%code%' AND 未完成` | `SystemDirectWriter.java:161` / `NotesBackend.java:203-214` |

App 侧指纹含地点，意味着：**同一个码，如果两次的地点字符串不同（策略升级导致提取结果变化、文案变了），会被当成两条各写一次。** 见 §9.2 #4。

---

## 6. 用户可见功能全清单

主界面全部用 Java 代码建 UI（无 XML 布局），`LauncherActivity` 1840 行，从上到下就是这个顺序：

```
┌──────────────────────────────────────────────────────────┐
│ 📦 取件码助手                       欧锴（O-kai）   ⋮   │  ← 固定头部，不随内容滚动
│ v3.1.0 · LSPosed 模块 · 自动提取取件码写入 小米笔记待办    │
├──────────────────────────────────────────────────────────┤
│ 💬 开发者 QQ 群：901543676          [📋 复制群号]        │
├──────────────────────────────────────────────────────────┤
│ 🩺 部署体检（可点按折叠）                                  │  状态卡
│ ✅ 一切正常 · 六项体检全部通过（规则集: v5 · 点开看详情）  │  全绿自动收成一行
│                                                          │  有 ❌ 强制展开
│ [🔍 排查问题（点不了 / 收不到？点这里）]   ← 仅未全绿时显示│
│ [🚀 一键部署 sqlite3]                      ← 仅缺失时显示 │
├──────────────────────────────────────────────────────────┤
│ ⚙️ 基础设置                                              │
│ 🎯 写入目标：小米笔记待办                    [切换]      │
│ 🌐 允许联网（自动更新）              已开启 / 已关闭     │
│ 🔔 通知取件提取（多平台）            已关闭 / 已开启     │
│ （📱 通知来源 App —— 界面未开放，见 §9.2 #2）               │
├──────────────────────────────────────────────────────────┤
│ 📝 待办模板                                              │
│ 极简 / 完整 / 自定义     效果预览                         │
│ [✏️ 编辑模板] → 输入框 + [💾保存][↩️恢复默认][取消]      │
├──────────────────────────────────────────────────────────┤
│ 🧩 用户自定义规则（高级选项）              未启用 / 已启用 │  整卡可点
│ 共 N 条，已启用 M 条 ｜ 当前官方规则集 v5                 │
├──────────────────────────────────────────────────────────┤
│ [🧪 一键链路测试（不消耗短信 · 分步验证）]                │
├──────────────────────────────────────────────────────────┤
│ 🚫 黑名单关键词                                          │
│ 当前：12306，验证码，余额…                                │
│ [✏️ 修改] → 输入框 + [💾保存][↩️恢复默认][取消]          │
├──────────────────────────────────────────────────────────┤
│ 🛡 冻结免疫直写（ColorOS 划掉后台仍可写入）                │  仅 ColorOS 显示
│ 已开启 / 已关闭                                          │  小米隐藏
│ ⚠️ 开启后需给「短信存储」授权 root……                      │
├──────────────────────────────────────────────────────────┤
│ 📖 使用说明（8 条）                                        │
└──────────────────────────────────────────────────────────┘
```

右上角 `⋮` 菜单：**🏠 项目仓库 / 🔄 检查更新（软件+规则）/ 📋 疑似漏抓错题本 / 💰 打赏作者**

### 功能清单表

| # | 功能 | 入口 | 做什么 | root | 需重启 | 需开关 |
|---|---|---|---|---|---|---|
| 1 | 自动提取取件码写待办 | 无（自动） | 收到取件短信/通知后自动写一条置顶待办 + 弹通知 | **必需** | 首次安装后需重启 | 无 |
| 2 | 部署体检（六项） | 主界面顶部 🩺 卡（点按折叠） | LSPosed 注入 / root / LSPosed 作用域 / 通知权限 / sqlite3 / 待办库，逐项给结论与修复指引 | 检测 root 本身需要 | 否 | 无 |
| 3 | 排查问题向导 | 主界面 [🔍 排查问题]（仅体检未全绿时显示） | 六层决策树逐层定位，每层给可执行动作；sqlite3 缺失时**自动部署** | 需要 | 提示「补作用域后需重启」 | 无 |
| 4 | 一键部署 sqlite3 | 主界面 [🚀 一键部署 sqlite3]（仅缺失时显示）或排查向导第 5 层 | 从 APK assets 释放 sqlite3 + 依赖 → su 拷到 `/data/local/tmp/pickup_sqlite/` | **必需** | 否 | 无 |
| 5 | 导出诊断报告 | 排查向导每层弹窗的 [📤 导出诊断报告] | 设备信息 + 体检 + 写库结果 + PICKUPDEBUG 日志 + LSPosed modules.log，**自动脱敏**手机号与取件码，存 Download 后拉起系统分享 | 需要（抓日志） | 否 | 无 |
| 6 | 写入目标切换 | 基础设置 [🎯 写入目标 · 切换] | 自动识别 / 小米笔记 / ColorOS 日历待办 / ColorOS 便签置顶笔记 | — | 否（下次写入即生效） | 无 |
| 7 | 允许联网（自动更新） | 基础设置 [🌐 允许联网] | 关闭 = 完全离线：软件检查 + 规则热更全停，核心功能零影响 | — | 否 | 开关本身 |
| 8 | 通知取件提取 | 基础设置 [🔔 通知取件提取] | 拦截菜鸟/菜鸟裹裹/京东/淘宝/拼多多/顺丰 6 个 App 的通知并提取取件码 | 间接（写库要） | 建议（追加包名必须重启） | 默认关 |
| 9 | 待办模板（极简/完整/自定义） | 📝 待办模板 卡 | 极简=只有码；完整=码+来源+地点+时间；自定义=占位符 `{code}{source}{place}{time}`。**必须含 `{code}`，长度 ≤500** | — | 否 | 无 |
| 10 | 用户自定义规则 | 主界面 🧩 卡片 | 写正则覆盖官方规则覆盖不到的驿站文案 | 间接 | 否（同步文件即时生效） | 规则页总开关「启用我的规则」 |
| 10a | ├ 规则编辑器 | 规则页 [➕ 新建规则] / 编辑 | ① 名称 ② 发送方范围（不限/精确/前缀/包含）③ 取件码正则 ★ ④ 取第几个捕获组 ★ ⑤ 来源 ⑥ 地点 ⑦ 地点正则 ⑧ 地点取第几个捕获组 + **必填测试样本** | — | 否 | 无 |
| 10b | ├ 规则自测 | 规则页规则卡片上的「🧪 测试」 | `dryRun()` 跑「规则集+发送方+短信」，显示命中码/来源/地点 | — | 否 | 规则本身 enabled |
| 10c | ├ 导出 / 导入分享 | 规则页 [📤 导出分享] / [📥 导入] | 导出为 `user-rules-v1` 文本（供 QQ 群分享 / GitHub Issue 贡献官方收录） | — | 否 | 无 |
| 10d | └ 参考案例 | 规则页编辑器 [🎓 参考案例] | 6 个可直接照抄的真实场景写法 | — | 否 | 无 |
| 10e | 三道安全防线 | 自动（保存时） | ① 危险正则静态拦截（嵌套量词/相邻 `.*`/量词叠加/>300 字符）② 强制自测样本（不通过不许启用）③ 50ms 耗时熔断 | — | — | — |
| 11 | 一键链路测试 | 主界面 [🧪 一键链路测试] | 3 步：① 输入短信 + 可选发送方（或一键填官方案例/粘贴剪贴板）② 解析（**不写库**，显示规则来源/码/来源/地点）③ 写入（走 Provider 真实链路，含去重与通知）。取件码**随机**生成，保证每次都能写进去 | **必需**（第 3 步要 su） | 否 | 无 |
| 12 | 黑名单关键词 | 🚫 黑名单 卡 [✏️ 修改] | 逗号分隔；正文含任一词则跳过。**清空 = 全放行**（有明确 Toast 提示，不是静默接受） | — | 否 | 无 |
| 13 | 冻结免疫直写 | 🛡 卡（仅 ColorOS 显示） | 模块 App 被 ColorOS 冻结时，由系统进程直接 su 写库。ColorOS 首启默认开；小米隐藏且强制关 | **必需，且需给 `com.android.providers.telephony` 授权 root** | **是** | 默认关（ColorOS 开） |
| 14 | 疑似漏抓错题本 | ⋮ 菜单 → 📋 | 列出「含快递特征词但提不出码」的短信（最多 30 条），[📋 复制全部上报文本] 已脱敏，[✍️ 手动上报] 跳 GitHub Issues 预填模板 | — | 否 | 通知通道需开（短信通道自动记录） |
| 15 | 检查更新（软件 + 规则） | ⋮ 菜单 → 🔄 | GitHub Releases API 查软件 tag + jsdelivr/raw 拉 `rules/rules.json`。**手动检查不受「允许联网」开关限制** | — | 否 | 自动检查需「允许联网」开 |
| 15a | └ 规则热更新 | 同上 | 版本更高且 `minAppVersionCode` 兼容时应用（先过 21 条冒烟门禁），写缓存并 `ProcessSync.push()` | — | 否 | 同上 |
| 16 | 通知交互 | 系统通知 | 点通知 = 复制全部码 + 打开待办 App；每个码一个 [已取件 XXXX] 动作按钮；一个 [查看待办] 按钮 | 「已取件」需要 | — | 通知权限 |
| 17 | 复制 QQ 群号 | 头部 [📋 复制群号] / 反馈渠道弹窗 | 复制 `901543676` | — | 否 | 无 |
| 18 | 反馈渠道 | 排查向导 [🐛 反馈给作者] | QQ 群 / GitHub Issues / 酷安帖子 | — | 否 | 无 |
| 19 | 打赏 | ⋮ 菜单 → 💰 | 支付宝/微信收款码，[💾 保存到 Download] | — | 否 | 无 |
| 20 | 项目仓库 | 头部署名 / ⋮ / 帮助 | 打开 GitHub | — | 否 | 无 |
| 21 | 版本号显示 | 头部副标题 | 直接读 `PackageManager`，**不在代码里写死版本号字符串**（`LauncherActivity.java:170-171` 注释说明了原因） | — | 否 | 无 |

---

## 7. 代码文件职责地图

`app/src/io/github/okaidev/pickupcode/` 下 22 个 `.java`（外加 `test/TierExclusionTest.java`），全部无第三方运行时依赖。

| 文件 | 行数 | 职责（一句话） |
|---|---|---|
| `XposedEntry.java` | 197 | LSPosed 模块入口：按进程名分流注册 8 个短信 Hook 点 + system_server 通知 Hook |
| `SmsBridge.java` | 313 | 短信 Hook 管道：多形态参数识别 → 多段拼接 → 进程内去重 → 转发模块 App |
| `NotiHook.java` | 169 | system_server 通知 Hook：白名单初筛 → 文本拼合 → 提取引擎 → 转发/记错题本 |
| `NotiRelay.java` | 76 | 从 NMS 服务对象反射取 system Context，把通知以「伪短信」广播给模块 App |
| `SystemDirectWriter.java` | 270 | 冻结兜底：模块 App 唤不醒时，在系统进程内 su sqlite3 直写目标库 |
| `ProcessSync.java` | 224 | 跨进程配置快照：App 进程 Base64 写 `app_state.json`，系统进程免 su 读（v3.1.0 新增） |
| `TodoProvider.java` | 99 | 跨进程事实入口：`ContentProvider.call` 接 `onSms` / `markDone`，冷启动时初始化规则引擎 |
| `SmsEventReceiver.java` | 63 | 广播入口：接 `ACTION_ON_SMS`，处理「命中」「漏抓样本」两种意图 |
| `TodoWriter.java` | 325 | 写库门面：黑名单 → 提取 → 去重 → 模板渲染 → su sqlite3 写入；来源识别也在这里 |
| `NotesBackend.java` | 251 | 三个写库后端的路径/表/置顶/完成 SQL 模板；后端自动识别与用户覆盖 |
| `PickupExtractor.java` | 637 | 提取引擎：规则加载与热替换、21 条冒烟门禁、四层判定、语义排除、地点三策略 |
| `ExtractorRules.java` | 312 | 规则数据模型 + JSON 序列化 + 全部正则编译（`compile()` 是唯一的正则拼装处） |
| `UserRules.java` | 891 | 用户自定义规则引擎：模型、存储、四种发送方匹配、优先级、三道防线、跨进程同步、导入导出 |
| `UserRulesActivity.java` | 720 | 用户规则页：总开关、列表、编辑器（①～⑧ + 测试样本）、参考案例、导入导出 |
| `LauncherActivity.java` | 1840 | 主界面：体检卡、基础设置、模板、用户规则入口、链路测试入口、黑名单、冻结直写开关、排查向导 |
| `ChainTestActivity.java` | 482 | 一键链路测试页：3 步 输入→解析→写入，独立 Activity（AlertDialog 承载 EditText 会吞触摸） |
| `DonateActivity.java` | 224 | 打赏页：支付宝/微信收款码展示与保存 |
| `Diagnostics.java` | 436 | 六项体检 + 诊断报告生成/脱敏/导出 + su 执行器（**注意 `suExec` 的引号替换**） |
| `Repair.java` | 201 | 自动修复：部署 sqlite3 到 `/data/local/tmp`；root 读 LSPosed 配置库比对作用域 |
| `UpdateCenter.java` | 268 | 更新调度中心：软件 + 规则集串行检查，各自 3 天懒检查，成功才记时间戳（v3.1.0 新增） |
| `MissedSmsStore.java` | 134 | 疑似漏抓错题本：本地存储（≤30 条）、去重、脱敏、上报文本格式化 |
| `Notifier.java` | 82 | 平台 API 弹通知：标题=码，正文=提示，动作按钮=已取件/查看待办 |
| `NetPolicy.java` | 44 | 联网总开关（`auto_net_allowed`，默认开）；关闭 = 纯离线 |

配置与资源：

| 文件 | 说明 |
|---|---|
| `app/AndroidManifest.xml` | versionCode/Name、权限、`xposedscope`、4 个组件（Provider/Receiver `exported=true`，3 个 Activity `exported=false`） |
| `app/res/values/arrays.xml` | LSPosed 推荐作用域 6 项 |
| `app/assets/rules/rules.json` | 规则副本 #2 |
| `app/assets/sqlite3/` | arm64 sqlite3 3.53.4 + 7 个依赖 .so（约 3.7 MB） |
| `rules/rules.json` | 规则副本 #1（远端热更源） |
| `test/check_rules_consistency.py` | 规则三副本一致性校验脚本 |
| `test/TierExclusionTest.java` | 语义排除回归（v3.1.0 新增，**尚未接入 `RulesRegression`，`test/README.md` 也没写**，见 §9.3 #19） |

---

## 8. ⚠️ 测试覆盖不到的地方

> **本章是本项目最大的风险点。**读完再改任何代码。

### 8.1 现在有哪些脱机回归

| 套件 | 文件 | 规模 | 覆盖什么 |
|---|---|---|---|
| 官方规则回归 | `test/RulesRegression.java` | **28 条取件码用例**（`test/sms-cases.txt`）+ **13 条地点用例**（内联数组，`:102-116`）+ 21 条冒烟 | `PickupExtractor` 的提取与地点逻辑 |
| 用户规则安全测试 | `test/UserRulesTest.java` | **45 项断言**（`check()` 计数；`test/README.md:92` 期望末行 `PASS=45 FAIL=0`） | 三道防线、捕获组、四种发送方匹配、优先级、导入导出往返 |
| 语义排除回归 | `test/TierExclusionTest.java` | **21 项**（8 项「该拦的拦住」+ 13 项「不该拦的别误伤」） | TierA/B 不得绕过 `isExcludedToken` |
| 规则三副本一致性 | `test/check_rules_consistency.py` | 5 类检查 | 规则文件一致性 + 隐藏字符 |
| 公开语料集 | `test/sms-corpus-public.jsonl` | 18 行（正例 12 / 反例 6） | **素材库，不是自动测试**——没有测试代码读它 |

最近一次结果（`test/reg-result.txt` 末行）：`TOTAL CODES: 28 PASS=28 FAIL=0`、`TOTAL PLACE: 13 PASS=13 FAIL=0`。

> 📌 **两个文件在本仓库中找不到**：`TraceTest` 与 `SmsLab`（全仓 grep 无匹配）。
> `CHANGELOG.md:70-71` 提到过「官方规则回归 38/38 ｜ 用户规则安全测试 55/55 ｜ 规则溯源测试 7/7」——
> 与仓库现状（28 / 45）不符，且「规则溯源测试」文件不存在。
> **待确认**：是这些文件被删了、被合并了，还是 CHANGELOG 记的是另一分支的状态。接手后建议先核对。

### 8.2 哪些问题编译期发现不了

| 类别 | 具体例子 | 为什么编译期抓不到 |
|---|---|---|
| **类不存在 / 类名写错** | `hookAllMethods(cls, "insert", …)` 里的 `"insert"`；`"com.android.providers.telephony.MiuiTelephonyProviderImpl"` | 是字符串，反射调用。第二个类在某些 ROM 上根本不存在（`XposedEntry.java:94`，代码 try/catch 兜着只打日志） |
| **Hook 点签名变化** | `enqueueNotificationInternal` 的参数位置；`SmsProvider.insert(Uri, ContentValues)` | Xposed API 不做签名校验；ROM 升级改了参数顺序只会运行期炸或静默不生效 |
| **系统进程拿不到的东西** | 系统进程里 `Context.getSharedPreferences` / `getFilesDir()` / `getAssets()` | 编译期完全合法，运行期在别的 UID 下抛异常或返回 null |
| **SELinux / DAC** | system_server 读 `/data/local/tmp/pickup_sqlite/app_state.json` | 该目录的 SELinux 标签在不同 ROM 上策略不同。**本项目实测可用，但换 ROM / 换 Android 版本必须重测（待确认）** |
| **私有库 schema** | `todo` / `Tasks` / `rich_notes` 的列名、列数、约束 | 目标 App 升级改了 schema，我们无从得知；SQL 拼错只会报 `no such column` |
| **su 授权状态** | Magisk 是否弹窗、用户是否选「允许」、是否勾了「不再询问」 | 运行期环境；未授权时 `su` 静默返回非 0 |
| **跨版本 API 差异** | `SmsMessage.createFromPdu(byte[], String)`、`getSerializableExtra` | minSdk 28 编译通过，不代表所有 ROM 实现一致 |
| **多线程安全** | `NotiHook.handleEnqueue` 里显式传 sender 不可存全局 | 静态字段的竞态编译期不报 |

### 8.3 哪些问题脱机回归发现不了

**核心原因一句话：**

> **`RulesRegression.NEED`（`test/RulesRegression.java:25-29`）只编译 8 个文件。**
> 22 个源文件里，**14 个从不参与脱机回归**：
> `XposedEntry` `SmsBridge` `NotiHook` `NotiRelay` `SystemDirectWriter` `ProcessSync`
> `TodoProvider` `SmsEventReceiver` `Notifier` `NetPolicy` `UpdateCenter`
> `LauncherActivity` `ChainTestActivity` `UserRulesActivity` `DonateActivity`

也就是说：**整个跨进程管道，脱机回归覆盖率是 0。**

| 测不到的东西 | 具体表现 |
|---|---|
| **跨进程配置传输** | `app_state.json` 写出来是不是有效 Base64？解出来是不是合法 JSON？内容有没有被 shell 改坏？—— 测试只验证 `buildJson()` 产出的**内存字符串**，完全不管它怎么穿过 shell |
| **shell 引号 / 转义** | `Diagnostics.suExec` 的 `cmd.replace("\"","'")` 是**运行期行为**。测试里没有 `su`，没有 `sh -c` |
| **Hook 是否挂上** | `XposedEntry` 从不进回归。`handleLoadPackage` 分流、`findClass` 是否成功，全部未测 |
| **跨进程通信是否通** | Provider 唤醒、广播派发、`Unknown authority`、冻结后的静默丢弃 |
| **真实写库** | `NotesBackend` 的 SQL 只在真机上对着真实 db 验证过；脱机无法执行 |
| **进程初始化时序** | `TodoProvider.onCreate()` 是否在第一条短信到达前跑完（v3.0.0 修的就是这个） |
| **并发** | system_server 通知回调多线程；Hook 进程与 App 进程同时写 SharedPreferences |
| **配置同步的「拉齐」效果** | 用户改了黑名单 → 兜底通道是否真的用了新值 —— 只有真机能验证 |
| **规则三副本的「第三份」** | 脚本只比文本内容，**不验证系统进程实际跑的是哪一份**。漏改 `createDefault()` 时脚本能过，但系统进程跑旧规则 |
| **UI 文件的编译错误** | 见 §8.4 案例 B |

### 8.4 两个真实案例

#### 案例 A：跨进程配置被 shell 引号替换，正则被静默篡改

**这是本项目已经真实发生过的事。**

```
症状：v3.1.0 做完「把生效规则同步给系统进程」的修复后，
      短信通道修好了，通知通道依然在漏抓，而且用户毫无感知。

根因链：
  ProcessSync.push()
    └ json = {"rulesJson":"{\"version\":5,\"keywordAnchors\":[\"取件码\",...]}", ...}
    └ Diagnostics.suExecPublic("cat > app_state.json << 'PICKUP_EOF'\n" + json + ...)
         └ sh -c 'su -M -c "cat > ... << PICKUP_EOF <json> ..."'
              └ suExec 里的 cmd.replace("\"", "'")
                   └ 落盘内容变成 {'rulesJson':'{\'version\':5,...
                        ↑ JSON 语法已破坏

为什么所有检测都过了：
  ✓ 编译通过      —— 这本来就是合法 Java 字符串
  ✓ RulesRegression 28/28 —— 它调用的是内存里的 activeRules，与落盘文件无关
  ✓ UserRulesTest 45/45 —— 同上
  ✓ 界面完全正常  —— 短信通道根本不走这个文件
  ✓ 没有报错      —— cat 成功了、chmod 成功了、JSON.parse 在 Java 侧失败被
                     catch 吞掉，只打了一条 Log.w，用户看不到

唯一的发现方式：在真机上 cat /data/local/tmp/pickup_sqlite/app_state.json，
               肉眼看到那些被替换掉的双引号。
```

同样的坑在 `user_rules.json` 上也踩过一次：`UserRules.java:285-287` 记着具体例子——
用户正则 `取件码["']?(\d+)` 里的双引号到了系统进程变成单引号，正则语义变了，**不报错、不崩溃，只是安静地提不出码**。

修复：全部改成 Base64（`ProcessSync.java:106-110`、`UserRules.java:289-293`）。

#### 案例 B：字段声明被误删，UI 文件编译不过，离线回归全绿

```
现象：2026-10-04 通读源码时发现 LauncherActivity.buildNotiPkgsRowContent()
      引用了未声明的字段 notiPkgsRow（字段声明位置只剩一段「保留字段与方法以便
      后续一键启用」的注释），整个模块 javac 报 "cannot find symbol"。
      当天已被修复（字段声明已恢复，全部 22 个源文件现已编译通过）。

为什么所有检测都过了：
  ✓ RulesRegression 28/28  —— LauncherActivity 不在 NEED 数组里
  ✓ UserRulesTest 45/45    —— 同上
  ✓ check_rules_consistency 通过 —— 那是 Python 脚本，跟 Java 无关
  ✓ 界面上"看起来"完全正常 —— 因为根本还没编译出 APK

只有 gradlew assembleDebug（或 javac 全量编译）能发现。
```

> **教训**：`LauncherActivity` / `UserRulesActivity` / `ChainTestActivity` 这类 UI 文件改完，**必须全量编译**，不能只看离线回归。

#### 案例 C：TierA/TierB 绕过语义排除（v3.1.0 发现并修复）

```
现象：2026-10-04 全量代码审计发现，TierA（关键词锚点）与 TierB（凭/出示）此前
      只做 validCode（码形检查），完全跳过 isExcludedToken（语义排除）。
      后果是 excludeTailWords / excludeHeadWords / 掩码手机号 / URL 这些排除规则
      对「最自信的那一类短信」恰好完全失效——而锚点短信正是用户投诉抓错码的主要来源。
      而原来的 21 条冒烟用例里没有一条是「锚点 + 语义噪声」，所以完全测不到。

修复：TierA/B 改走带原文位置的 addIfValidSemantic()；
      新增 test/TierExclusionTest.java（21 项）锁住「该拦的拦住」+「不该拦的别误伤」。
```

### 8.5 结论与规矩

```
┌────────────────────────────────────────────────────────────────────┐
│  测试全绿 ≠ 功能正常                                                  │
│                                                                    │
│  编译通过  ── 只证明「语法和类型没问题」                              │
│  回归全绿  ── 只证明「单进程内的纯逻辑没问题」                        │
│  界面正常  ── 只证明「模块 App 进程这一条链路没问题」                 │
│                                                                    │
│  剩下两条链路（system_server / providers.telephony）                │
│  只能靠真机验证。                                                    │
└────────────────────────────────────────────────────────────────────┘
```

**今后改动只要沾到下面任意一项，必须真机验证，不能只看测试通过：**

- [ ] **跨进程**：任何 `/data/local/tmp/pickup_sqlite/` 下的文件（内容、格式、路径、权限）
- [ ] **命令行**：任何经过 `Diagnostics.suExec` / `suExecPublic` / `Runtime.exec` 的字符串
- [ ] **数据同步**：`ProcessSync` / `UserRules` 的写入端或读取端
- [ ] **Hook 点**：`XposedEntry` / `NotiHook` 里任何类名、方法名、参数下标
- [ ] **写库**：`NotesBackend` 的任何 SQL、路径、后端判定
- [ ] **进程入口**：`TodoProvider.onCreate` / `SmsEventReceiver.onReceive` / `NotiHook.install` / `SystemDirectWriter.fallback` 的初始化时序

**真机验证最小清单**：

```bash
# 1. 配置快照落盘正确？
su -c "cat /data/local/tmp/pickup_sqlite/app_state.json" | head -c 200
#    ↑ 必须是纯 Base64（只有 A-Za-z0-9+/=）
su -c "cat /data/local/tmp/pickup_sqlite/app_state.json" | base64 -d | head -c 300
#    ↑ 必须是合法 JSON

# 2. 用户规则落盘正确？
su -c "cat /data/local/tmp/pickup_sqlite/user_rules.json | base64 -d"

# 3. 两个开关文件在位？
su -c "ls -l /data/local/tmp/pickup_sqlite/enable_*"

# 4. Hook 真的挂上了？
logcat -d -s PICKUPDEBUG:* | grep -E "S[1-8] OK|NotiHook installed|handleLoadPackage"

# 5. 系统进程实际用的规则版本是几？
logcat -d -s PICKUPDEBUG:* | grep "user rules loaded"   # 应显示 official rules v5
```

---

## 9. 已知问题与限制

> 如实列出，包括会让接手的人踩坑的地方。

### 9.1 曾发生、已修复（保留记录，因为它们是活的风险类型）

| 案例 | 说明 |
|---|---|
| TierA/TierB 绕过语义排除 | 见 §8.4 案例 C，已修 + 已加回归 |
| `notiPkgsRow` 字段声明被误删导致编译失败 | 见 §8.4 案例 B，已修 |

### 9.2 功能缺口

| # | 问题 | 位置 | 说明 |
|---|---|---|---|
| 1 | **通知来源 App 自定义：后端已就绪，界面未开放** | 后端全部实现完毕（`ProcessSync.notiPkgsOf/setExtraNotiPkgs`，`:148-179`；`NotiHook` 合并，`:62-70`）。界面注释明确写「经与作者确认暂不开放」（`LauncherActivity.java:303-308`、`:717`） | 想抓闪送 / 自建驿站 App 的通知仍需等发版。用户可手工改 `noti_extra_pkgs` 偏好但没 UI |
| 2 | **错题本无删除入口** | `MissedSmsStore.clear()`（`:76-79`）**定义了但全仓无任何调用点** | 错题本只增不减（上限 30 条，按正文去重/淘汰），用户看过的样本清不掉，也没有「全部清空」按钮 |
| 3 | **去重指纹含地点，同码可能重复写入** | `TodoWriter.java:89`：`fingerprint = code + "|" + place` | 同一条短信两次提取出的地点字符串不同（策略升级导致提取结果变化、文案变了），指纹就不同 → **同一个取件码被写两条待办**。改成只按 `code` 去重可解决，但会误杀「不同批次恰好重号」。**这是有意的取舍，但代码里没有写下理由** |
| 4 | **来源识别仍是硬编码，无法热更** | `TodoWriter.resolveSource()`（`:256-…`） | 12 条 `if (t.contains(...)) return "..."` + 括号品牌名启发式。新驿站品牌只能靠发版。不过策略 1（取正文开头的 `【】`/`[]` 括号品牌名，限 12 字）已覆盖大部分新品牌 |
| 5 | **写入目标（backend）没有进跨进程快照** | `ProcessSync.State`（`:49-55`）**没有 `backend` 字段**；`SystemDirectWriter.fallback()` 用 `NotesBackend.propsBackend()`（`:148`） | 系统进程无 Context，只能按 ROM 属性猜。**用户在设置页手动把写入目标改成与 ROM 推断不同的后端时，兜底通道会写到另一个库**。v3.1.0 同步了黑名单/模板/规则，唯独漏了这个 |
| 6 | **通知链路没有冻结兜底** | `NotiRelay.java:14-16` 明说 | 通知通道只有广播，模块 App 冻结时通知提取的码会丢。短信通道有 `SystemDirectWriter` 兜底，通知通道没有 |

### 9.3 代码质量 / 维护债

| # | 问题 | 位置 |
|---|---|---|
| 7 | `RulesRegression.java:79` **硬编码绝对路径**读 `sms-cases.txt`，忽略了第 33 行的 `repo` 参数 → 换机器/换路径跑不了 | `test/RulesRegression.java:79` |
| 8 | `RulesRegression.java:35,45` 的默认仓库路径 / android.jar 路径是本机绝对路径 | 同上 |
| 9 | `LauncherActivity` 有一批死代码：`showParseResult()`（`:1290-…`，无调用点）、`groupTitle()`（`:1791`）、`groupDesc()`（`:1803`）、`spacer()`（`:1834`） | `LauncherActivity.java` |
| 10 | `UpdateCenter.run()` 里有一个**空的 if 块**（只有注释、无任何语句），是改时间戳逻辑时的残留 | `UpdateCenter.java:92-94` |
| 11 | `SystemDirectWriter.FLAG_ASSET`（`:43`）声称可回落 APK assets 兜底，但 `readFlag()` 第 2 步直接 `return false`（`:97-98`），**回落逻辑未实现，待确认** | `SystemDirectWriter.java` |
| 12 | `Repair.SCOPE_OPTIONAL`（`:45`）定义了但**无任何引用** | `Repair.java` |
| 13 | `PickupExtractor` 有多组不带 sender 的重载（`extract(String)`、`hasFeatureWords(String)`、`lookLikePickupSms(String)`），保留是为了兼容脱机测试；**生产路径应一律用带 sender 的版本**（system_server 多线程下共享 sender 不可靠） | `PickupExtractor.java` |
| 14 | `Diagnostics.sanitize()` 的取件码正则 `\b\d{1,4}-\d{1,2}-\d{1,5}\b`（`:44`）**只覆盖横线簇**，纯数字码（`72778`、`A88123`）不会被脱敏 | `Diagnostics.java` |
| 15 | `/data/local/tmp/pickup_sqlite/` 在 `Repair`、`ProcessSync`、`UserRules`、`SystemDirectWriter`、`TodoWriter` **5 个类里各自硬编码**，改路径要改 5 处 | 见 §3.2 |
| 16 | `TodoWriter.MODE_MIN/FULL/CUSTOM`（`:50-52`）与 `ProcessSync.State.todoMode` 默认值 `1` 是**两处独立维护的魔数** | |
| 17 | `CHANGELOG.md:70-71` 声称的质量门禁数字（38/38、55/55、7/7）与仓库现状（28、45）对不上，且「规则溯源测试」文件不存在 | `CHANGELOG.md` |
| 18 | `ProcessSync.buildJson()` 用**反射读私有静态字段** `PickupExtractor.activeRules`（`:124`）。重命名该字段不会编译报错，只会在运行期静默丢失 `rulesJson` | `ProcessSync.java` |
| 19 | `test/TierExclusionTest.java`（v3.1.0 新增）**尚未接入 `RulesRegression`**，也没有写进 `test/README.md` → 跑 `RulesRegression` 时它根本不会执行 | `test/` |
| 20 | **本仓库正在被并行修改**（撰写本文档期间 `LauncherActivity` / `PickupExtractor` / `TodoWriter` 等 11 个文件被改动）。合并/评审前请先 `git status` 确认工作区状态 | — |

### 9.4 设计上的固有边界（不算 bug，但要知道）

- **必须给系统进程授权 root**（兜底直写开启时）。给常驻系统进程开 root 会扩大攻击面 —— 设置页有风险提示，这是自觉的取舍。
- **`/data/local/tmp` 是全局可读位置**。虽然内容已 Base64、用户规则的测试样本已剥离、诊断报告已脱敏，但仍然是「任何 app 用 root 都能读」。代码里对此有明确自查（`UserRules.java:272-275`、`Diagnostics.java:32-35`）。
- **冒烟门禁会拒绝「变聪明」的新规则**。21 条黄金用例要求提取结果**完全相等**，所以任何让某条老用例多提一个码的规则改动都会被拒。这是刻意的（防「修一个坏一个」），但改规则时要有心理准备。
- **通知提取的白名单只有 6 个包名**（且用户追加入口未开放，见 #1）。没在白名单里的 App 的通知一律不看。
- **不支持验证码 OTP 等场景**。`negativeKeywords` 里有「验证码」，且冒烟用例含 3 条验证码反例。这是产品定位，不是缺陷。

---

## 10. 给维护者的注意事项

### 10.1 改规则：三处同步 + 跑脚本

```bash
# 1. 改这三份（内容必须一致）
#    rules/rules.json
#    app/assets/rules/rules.json
#    app/src/io/github/okaidev/pickupcode/ExtractorRules.java  ← createDefault()

# 2. 校验（提交前必跑）
python test/check_rules_consistency.py
#    退出码 0 = 一致

# 3. 跑回归
cd test
javac -encoding UTF-8 RulesRegression.java
java RulesRegression /path/to/repo /path/to/android-34/android.jar
#    末行应为 TOTAL CODES: 28 PASS=28 FAIL=0 / TOTAL PLACE: 13 PASS=13 FAIL=0
```

**漏改第三份的后果**（`test/check_rules_consistency.py:16-18` 原文）：系统进程一直跑旧规则，短信通道是好的，界面完全看不出异常，只有通知取件悄悄漏抓。

### 10.2 🚫 不要用行级脚本批量删 Java 代码

> **本项目历史上误删过带 `@Deprecated` 注解的方法，也误删过普通字段声明（§8.4 案例 B）。**

原因是按行匹配/按正则删除的脚本会把「方法体 + 上方注解 + 文档注释」当成一个整体处理，遇到注解块、跨行注释或「注释紧挨着下一个字段」就错位，删掉本该保留的东西。

**正确做法**：

- 删代码前先 `grep -n` 确认真实行号，**人肉读一遍上下文**
- 一次只删一个逻辑单元，删完立刻 `grep` 确认目标符号还在 / 已不在
- 大改优先用 IDE 重构，而不是 `sed -i` / `Get-Content | Where-Object` 之类的行级管道
- **删完之后必须全量编译**（见 §10.3），不能只跑离线回归

### 10.3 本地全量编译（不需要 Android Studio）

`gradlew assembleDebug` 需要完整 SDK；如果只想验证「能不能编译」，用 `javac + android.jar` 更快：

```bash
# 1. 需要两样东西
#    · android.jar：$ANDROID_HOME/platforms/android-34/android.jar
#    · Xposed API：de.robv.android.xposed:api:82（compileOnly，本机未必有）
#      没有的话，写 5 个 20 行的 stub 类即可（XposedBridge / XposedHelpers /
#      XC_MethodHook / IXposedHookLoadPackage / callbacks.XC_LoadPackage）
#      —— 它们只被用于「方法存在性」检查，本体可以是空实现。

# 2. 全量编译
javac -encoding UTF-8 -nowarn -cp "$ANDROID_JAR" -d /tmp/out \
  app/src/io/github/okaidev/pickupcode/*.java  /tmp/xpstub/**/*.java
echo "exit=$?"

# 当前状态：22 个源文件全部编译通过（exit=0）
```

> 这条命令能抓到 §8.4 案例 B 那类「UI 文件里的字段/引用错误」，而离线回归抓不到。**改完 UI 必跑。**

### 10.4 🚫 PowerShell 改文件必须无 BOM 写入

PowerShell 的 `Set-Content -Encoding UTF8`、`.NET File.WriteAllText` 的默认编码、以及 `>` 重定向，在 Windows PowerShell 5.1 下都会写 **UTF-8 with BOM**。

对 `.java` 文件，BOM 会让 javac 报：

```
error: illegal character: '\ufeff'
```

> 这不是假设——撰写本文档时用 `Set-Content -Encoding UTF8` 写 Xposed stub，当场就复现了这个报错。

**安全写法**：

```powershell
# ✅ .NET 显式指定无 BOM 的 UTF-8
$enc = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($path, $content, $enc)

# ❌ 危险
Set-Content $path $content -Encoding UTF8      # PowerShell 5.1 会加 BOM
Get-Content $f | Where-Object {...} > $g      # 重定向编码依赖 $OutputEncoding
```

**验证**：

```powershell
$b = [System.IO.File]::ReadAllBytes($p)
if ($b[0] -eq 0xEF -and $b[1] -eq 0xBB -and $b[2] -eq 0xBF) { "有 BOM！" } else { "无 BOM OK" }
```

`test/check_rules_consistency.py` 也会扫规则文件里的 BOM / 零宽空格 / NBSP / 全角空格，**但只扫两份 JSON，不扫 `.java`**。

### 10.5 新增跨进程文件

见 §3.6 的 checklist。核心一条：**内容先 Base64（`NO_WRAP`），别把原始字符串内联进 shell 命令。**

### 10.6 加新功能时的位置约定

| 想加什么 | 放哪 |
|---|---|
| 新的 Hook 点 | `XposedEntry.handleLoadPackage()` 的进程分流里，`try/catch Throwable` 包住 |
| 新的提取规则 | `rules/rules.json`（记得三份同步）+ 冒烟用例 |
| 新的写入目标库 | `NotesBackend` 加后端常量 + `*Of()` 系列 + `LauncherActivity.showBackendPicker()` 的 `values`/`items` |
| 新的用户设置 | `SharedPreferences("dedup")` 加键 + **同步加进 `ProcessSync.buildJson()` 和 `State`**，否则系统进程看不到 |
| 新的诊断项 | `Diagnostics.healthCheck()` + `LauncherActivity.isBlockingCheck()`（决定是否阻断链路测试） |
| 新的 UI 区块 | `LauncherActivity.onCreate()` 的卡片区（注意 `smallButton()` **不能**在内部 `setLayoutParams`，见 `:1757-1758` 注释） |
| 新的回归用例 | `test/sms-cases.txt`（取件码）/ `test/UserRulesTest.java`（用户规则）/ `test/TierExclusionTest.java`（语义排除） |

### 10.7 每次提交前

```bash
# 1. 全量编译（能抓到 UI 文件里的字段/引用错误）
#    见 §10.3

# 2. 规则一致性
python test/check_rules_consistency.py

# 3. 官方规则回归
cd test && javac -encoding UTF-8 RulesRegression.java && \
  java RulesRegression <repo> <android.jar>       # 期望 28/28 + 13/13 + Smoke PASS

# 4. 用户规则安全测试
javac -encoding UTF-8 -nowarn -cp "<android.jar>" -d out \
  ../app/src/io/github/okaidev/pickupcode/{UserRules,Repair,Diagnostics,TodoWriter,PickupExtractor,ExtractorRules,MissedSmsStore,NotesBackend}.java
javac -encoding UTF-8 -nowarn -cp "<android.jar>;out" -d out UserRulesTest.java
java -cp "out;<android.jar>" io.github.okaidev.pickupcode.UserRulesTest   # 期望 PASS=45 FAIL=0

# 5. 语义排除回归（当前未接入 RulesRegression，需手动）
```

> 如果改动涉及 §8.5 清单里的任何一项，**以上全绿也不算数**，必须加一轮真机验证。

### 10.8 相关文档

| 文档 | 内容 |
|---|---|
| `docs/17-coloros-adaptation.md` | ColorOS 16 真机侦察（tasks.db 字段约定取样过程） |
| `docs/user-rules-guide.md` | 用户规则完整参考手册（App 内也有入口） |
| `docs/HISTORY.md` | 完整开发历程 |
| `docs/09-write-path-verification.md` | 写库路径验证记录 |
| `docs/14-hooks.md` | Hook 点清单 |
| `test/README.md` | 脱机回归怎么跑、依赖链说明（**未收录 `TierExclusionTest`，需补**） |
| `CHANGELOG.md` | 版本变更（数字与仓库现状有出入，见 §9.3 #17） |

---

## 附录：常用命令速查

```bash
# 看模块日志
adb logcat -d -s PICKUPDEBUG:* | tail -n 400

# 看某条短信为什么没被提取
adb logcat -d -s PICKUPDEBUG:* | grep -E "PROVIDER-CALL|EVENT:|dedup skip|TODO OK|TODO FAIL"

# 看 Hook 挂上了没
adb logcat -d -s PICKUPDEBUG:* | grep -E "S[1-8] (OK|NOT FOUND)|NotiHook installed"

# 看跨进程配置（注意必须是合法 Base64）
adb shell su -c "cat /data/local/tmp/pickup_sqlite/app_state.json" | base64 -d | head -c 500
adb shell su -c "cat /data/local/tmp/pickup_sqlite/user_rules.json" | base64 -d

# 直接查待办库（小米笔记）
adb shell su -c "LD_LIBRARY_PATH=/data/local/tmp/pickup_sqlite/lib /data/local/tmp/pickup_sqlite/sqlite3 /data/user/0/com.miui.notes/databases/todo.db 'SELECT content FROM todo ORDER BY create_time DESC LIMIT 5;'"
```

---

*本文档基于 `versionCode 310` 源码通读生成，行号快照时间 2026-10-04 约 02:50。*
*该时刻仓库正在被并行修改，后续提交后行号可能漂移 —— **以文件内容为准，不要以行号为准**。*
