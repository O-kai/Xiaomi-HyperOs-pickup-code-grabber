# 4.0 目标架构

> 设备级 hack 红线见 `HANDOFF-INTEGRATION.md` 第二节：sqlite3 16KB 对齐、部署路径、通知字段补全、白名单顺序等，改动前必须有等价替代 + 真机验证。

> 原则：**保留真机资产，替换应用骨架**。Hook、提取、ColorOS/小米写入知识不能重写；应用层围绕“本地取件库”重建。

## 一、目标与非目标

**目标**

- 本地取件库成为唯一事实源；系统待办是可选的写穿出口。
- 短信/通知捕获链路保持现有覆盖率，不因重构降低。
- 应用进程与 Hook 进程彻底隔离：Compose/Room 只进 App，不进 system_server。
- 关闭未授权的跨进程入口，保留系统进程唤醒能力。
- 规则只有一份源，构建时生成所有副本。
- 所有失败可解释、可诊断、可重试。

**非目标**

- 不在 4.0 重写提取算法；先做特征化测试锁定行为，再逐步拆分。
- 不在 4.0 做无 root 读取；Lite 放 5.0。
- 不引入云、账号、统计、广告。
- 不为了“架构漂亮”删除设备级 hack。

## 二、目标模块

```
:app        Kotlin + Compose + Room + Widget + 通知 + 诊断 + 后端编排
:core       纯 JVM：模型、提取器、规则、归一化、指纹、模板、脱敏
:contract   纯 Java：Provider authority、method、extras key、事件 DTO
:hook       Java：Xposed 入口、SMS/通知 Hook、跨进程上报（禁止 AndroidX）
:backend    Java：小米笔记 / ColorOS 日历·便签 SQL 适配器（禁止 AndroidX）
```

依赖方向：

```
:app -> :core, :contract, :hook, :backend
:hook -> :core, :contract, :backend
:core -> 无 Android 依赖
:contract -> 无 Android 依赖
:backend -> Android API（但无 AndroidX）
```

强制约束：

- `:hook` 的依赖树里不能出现 `androidx.*`、Compose、Room、Kotlin Coroutines Android。
- `:hook` 不读取 App 私有目录；只通过 Provider 上报，或读取 `/data/local/tmp` 的只读快照。
- `:app` 不直接把 Compose 类型传给 Hook。
- `:core` 可以在普通 JVM 上跑全部提取测试。

## 三、运行时拓扑

```
短信进程 / system_server / telephony
  XposedEntry
    ├─ SmsBridge（S1–S11、SmsProvider.insert）
    ├─ NotiHook（通知文本）
    └─ SystemDirectWriter（冻结免疫，可选）
          │
          │ ContentResolver.call（UID 白名单）
          ▼
模块 App 进程
  TodoProvider
    ├─ 归一化 + 提取（:core）
    ├─ 本地库（Room，唯一事实源）
    ├─ 去重 / 事件日志
    ├─ 写穿后端（小米笔记 / ColorOS，可选）
    ├─ 通知（复制 / 已取件 / 打开详情）
    └─ 小组件刷新
```

### 必须保留的现有资产

- S1–S11 Hook 候选类名，尤其 ColorOS 的 `InboundSmsHandler$SmsBroadcastReceiver`、`PrivilegedSmsReceiver`、`SmsReceiver`、`SmsDeliverReceiver`、`SmsReceivedReceiver`。
- `SmsProvider.insert` 主通道；PDU 解析、多段短信拼接、Base64 跨进程快照。
- `ProcessSync` 的“先写 .tmp 再原子改名”和去样本逻辑。
- `NotesBackend` 三种后端的表名/列名/SQL；`SystemDirectWriter` 的 `su -M` 路径。
- `Diagnostics` 的结构自适应 LSPosed 作用域检查、报告脱敏思路。
- 内置 16KB 对齐的 sqlite3 与依赖库。
- `test/` 下的真实语料与回归用例。

## 四、本地优先数据流

```
RawMessage(channel, sender, body, ts, package)
  -> normalize()
  -> PickupExtractor.extract() -> ExtractionResult(codes, source, place, tier, ruleId, reasons)
  -> eventHash = SHA-256(channel|sender|normalizedBody|tsBucket)
  -> DB transaction:
       if event_hash exists: touch last_seen, stop
       if active (code, place) exists: touch last_seen, stop
       insert pickup_record(status=PENDING)
       insert outbox(record_id, target)
  -> notify + widget update
  -> outbox worker -> PickupSink.write() -> backend_ref / error
```

关键点：

- **先落本地，再写系统待办**；系统待办失败不影响本地可见。
- 写穿失败进入 outbox 重试，不静默丢。
- 通知和小组件只读本地库，不直接读系统待办。
- 标记已取件：本地立即更新；系统待办异步同步；失败可重试。

## 五、数据模型（Room）

### pickup_records

| 列 | 类型 | 说明 |
|---|---|---|
| id | TEXT PK | UUID |
| code | TEXT | 归一化取件码 |
| source | TEXT? | 菜鸟/丰巢/…，未知为 null |
| place | TEXT? | 地点，未知为 null |
| received_at | INTEGER | 短信/通知时间 |
| created_at / updated_at | INTEGER | 本地时间 |
| status | TEXT | PENDING / PICKED_UP / ARCHIVED |
| picked_at | INTEGER? | 标记时间 |
| last_seen_at | INTEGER | 去重触摸 |
| event_hash | TEXT UNIQUE | 事件幂等 |
| backend | TEXT? | 写穿目标 |
| backend_ref | TEXT? | 目标库 ID / 码 |
| sync_state | TEXT | NONE / PENDING / OK / FAILED |
| sync_error | TEXT? | 脱敏原因 |
| note | TEXT? | 用户备注 |

### pickup_events

| 列 | 类型 | 说明 |
|---|---|---|
| id | TEXT PK | UUID |
| record_id | TEXT? | 关联记录 |
| channel | TEXT | SMS / NOTI / MANUAL |
| sender_hash | TEXT? | 发送方哈希，不存原号码 |
| body_hash | TEXT | 正文哈希 |
| received_at | INTEGER | 时间 |
| result | TEXT | EXTRACTED / DEDUP / REJECTED / ERROR |
| reason | TEXT? | 层级/规则/失败原因 |
| raw_text | TEXT? | **默认 null**；用户开启“保留原文”才写 |

### outbox

| 列 | 类型 | 说明 |
|---|---|---|
| id | TEXT PK | UUID |
| record_id | TEXT | 关联记录 |
| target | TEXT | xiaomi / coloros_todo / coloros_note |
| action | TEXT | WRITE / MARK_DONE |
| attempts | INTEGER | 重试次数 |
| next_at | INTEGER | 退避时间 |
| last_error | TEXT? | 脱敏错误 |

### 去重规则

- `event_hash` 唯一：同一事件（同通道、同发送方、同正文、同时间桶）只处理一次。
- 活跃记录 `(code, place)` 在 24h 窗口内只建一条；重复短信只更新 `last_seen_at`。
- 系统待办侧再做一次精确码匹配，避免升级后重复写。
- 现有 `LIKE '%code%'` 改为边界匹配（前后不是数字/字母/横线），避免子串误命中。

## 六、跨进程安全

### 现状问题

- `TodoProvider` exported=true 且无调用方校验（`TodoProvider.java:36-57`）。
- `SmsEventReceiver` exported=true（`AndroidManifest.xml:59-61`）。
- `LauncherActivity` exported=true，并直接处理外部 `done` extra（`LauncherActivity.java:2065-2070`）。
- 任意普通 App 可伪造短信、触发写库/通知，或把码标记已取件。

### 目标设计

- **只保留一个 exported 入口**：`TodoProvider`。
- `onSms`：允许 UID 1000（system）、1001（phone/telephony provider）、`com.android.mms` 的 UID、自身 UID；其他一律拒绝并记录。
- `markDone`：只允许自身 UID；通知动作走 App 内部非导出组件。
- 入参限制：body ≤ 2000 字；ts 不得超过当前时间 1 天；每 UID 每秒最多 1 次。
- 删除 `SmsEventReceiver` 的 exported；通知 Hook 改用 Provider 的 `onSms` / `onMissed`。
- 通知“复制/已取件”改到非导出 `NotificationActionReceiver` / 透明 Activity；`LauncherActivity` 不再接受 `done`/`copy` extras。
- 小组件 PendingIntent 使用唯一 `data` URI；全部 `FLAG_IMMUTABLE`。
- 不用 `android:permission` 作为唯一防线：系统进程不持有 App 签名权限；UID 白名单是主防线。
- 诊断报告默认不写短信正文；release 日志不打印 body；错题本默认关闭/自动过期。

## 七、写入后端

```java
interface PickupSink {
    String id();                       // xiaomi / coloros_todo / coloros_note
    boolean isAvailable(Context ctx);
    WriteResult write(PickupRecord r); // 幂等
    WriteResult markDone(PickupRecord r);
    Health health(Context ctx);        // 表存在/字段/权限
}
```

- `LocalSink` 永远存在，不是“可选后端”；它是事实源。
- 系统后端是附加出口；默认“本地 + 自动识别的系统待办”，用户可改“仅本地”。
- 写穿失败：本地保留，outbox 重试；通知不重复弹，只在设置/详情显示同步失败。
- 老用户升级：保留原写入目标；本地库从 4.0 开始记录；界面说明“原有待办仍在系统 App 中”，不做自动导入（避免读私有库）。
- ColorOS：保留现有三种后端；没有真机时标记“社区验证”，不阻塞小米主线。

## 八、规则单源

- 唯一源：`rules/rules.json`。
- Gradle 任务生成 `RulesDefaults.java` 并复制到 `app/assets/rules/rules.json`；构建时校验 SHA-256。
- `:core` 只依赖生成后的常量；系统进程无 Context 也能拿到同一份。
- 测试 `check_rules_consistency.py` 改为校验“源 → 生成物”一致，不再人工维护三份。
- 规则 schema 增加 `version / minAppVersionCode / keywordAnchors / featureWords / negativeWords / patterns` 校验；空数组拒绝加载。
- 远程更新：HTTPS + 版本号 + SHA-256；后续可选 Ed25519 签名。
- 提取结果带 `tier / ruleId / reasons`，链路测试和诊断可解释“为什么命中/为什么没命中”。

## 九、桌面小组件

- `PickupWidgetProvider` 在 App 进程；只读 Room。
- 固定 3 行，不用 collection/RemoteViewsService；避免 Android 16 弃用 API。
- 每次 DB 变更发 `AppWidgetManager.updateAppWidget`；`updatePeriodMillis=0`。
- 行点击：复制（透明 Activity，确保前台剪贴板） / 已取件（非导出 Receiver） / 打开详情。
- 深浅色资源独立；空状态、加载态、隐私模式（只显示件数）。
- 开机/升级/App 启动补一次刷新。

## 十、诊断与隐私

- 报告改为 JSON + 人类摘要：设备、版本、Hook 能力矩阵、作用域、root、sqlite3、后端 schema、本地库、小组件、最近错误。
- 不记录短信正文；只记录 hash、层级、规则 ID、失败原因。
- 日志分级：release 只记元数据；debug 才允许正文片段，且默认关闭。
- 导出前显示“将分享什么”的预览；脱敏覆盖手机号、身份证、取件码、地址关键词。
- 错题本只存脱敏文本/哈希，默认 30 天过期，可一键清空。

## 十一、构建与工具链

- `version.properties` 单一版本源；Manifest 删除硬编码版本。
- **4.0 生产建议（稳定优先）**：沿用已验证的 Gradle 8.6 + AGP 8.4.2 + Kotlin 1.9.24 + Compose BOM 2024.06.00 + Room 2.6.1；AGP 8.7+/Kotlin 2.x 作为 4.1 升级项，不在 4.0 冒险。
- **已完成的 spike（2026-10-30）**：上述组合在 `compileSdk 34` 可构建；debug APK 约 20MB，正式版必须开 R8 并保留 Hook 反射类。
- `compileSdk`/`targetSdk` 先做 spike：候选 `compileSdk 36 / targetSdk 35`；若 K90 Pro Max 上出现 edge-to-edge、通知、16KB 页问题，4.0 先保持 34，后续 4.0.x 再升。
- `:hook` 不使用 R8 混淆敏感类；release 先保持 `minifyEnabled false`，后续再加 keep 规则。
- CI：单元测试、规则一致性、lint、assembleDebug；tag 时构建 release 测试包。
- 保留 16KB 对齐 sqlite3；新增 ELF 对齐检查脚本。

## 十二、性能预算

| 项 | 预算 |
|---|---|
| Hook 入口耗时 p95 | < 20ms |
| 单条提取 p95 | < 5ms |
| App 冷启动到首屏 | < 500ms（缓存优先） |
| 小组件更新 | < 1s |
| 主线程 root/sqlite3 | 0 次 |
| ANR / 崩溃 | 0（发布前 48h soak） |

## 十三、风险与对策

| 风险 | 对策 |
|---|---|
| Compose/Room 升级导致构建链不稳 | Stage 0 先 spike；失败则保持 Java UI，分阶段迁移 |
| targetSdk 升级触发 Android 17 行为变更 | 先 34 保稳，单独兼容性任务；不阻塞 4.0 核心 |
| 小米笔记/ColorOS schema 变化 | 后端健康检查 + 本地库兜底 + 可切换“仅本地” |
| 小组件 OEM 刷新限制 | 事件驱动 + 开机/启动补刷 + 固定行不用 collection |
| 安全回归 | UID 白名单 + 未授权调用测试 + 安全 checklist |
| 范围膨胀 | P0/P1 分层；P1 不阻塞 4.0 发布 |

## 十四、必须保留的设备级知识

- ColorOS/小米的 Hook 类名候选与进程归属。
- 多段短信拼接、PDU 解码、发送方与正文并发错配的处理。
- `ProcessSync` 的 Base64、原子写、去样本、配置快照。
- `NotesBackend` 三种后端的表/列/SQL 与 `SystemDirectWriter` 的 `su -M`。
- LSPosed 作用域结构自适应读取。
- 16KB sqlite3 与依赖库部署路径。
- 现有测试语料与真实反馈案例。

> 重构时先写“特征化测试”锁住这些行为，再动代码；没有测试覆盖的 hack 不删。
