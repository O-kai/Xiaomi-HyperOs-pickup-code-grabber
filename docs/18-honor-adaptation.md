# 荣耀（MagicOS）适配：设计、已验证的部分与真机待确认项

> 版本：v4.1.0 ｜ 目标系统：MagicOS 7 / 8 / 9 / 11（Android 12–17）
> 第一台真机（HONOR · MagicOS 11 / Android 17）的侦察与端到端验证结论已回填到
> 第九～十二节：「待办库 = task.db」「表名 Tasks」「完成列 complete」
> 「Provider authority = com.android.calendar.task」这几条都已在真机上确认并写进代码。

---

## 〇、结论速览

| 侧 | 结论 |
|---|---|
| 短信捕获 | **基本零改动**。主通道 S8 挂在 AOSP 的 `SmsProvider.insert/bulkInsert`，荣耀沿用；S1 挂在 `InboundSmsHandler$SmsBroadcastReceiver`，也是 AOSP 组件。新增的只有「荣耀信息」进程（`com.hihonor.mms`）里的接收器候选 |
| 通知捕获 | **零改动**。挂在 system_server 的通知管线，与 ROM 无关 |
| 待办写入 | **新增后端 `honor_todo`**：先走荣耀待办 Provider（`com.android.calendar.task`，不需要 root，且会触发系统刷新），失败再按真实表结构直写 `task.db` |
| 表结构 | **不写死**。运行时 `PRAGMA table_info(Tasks)` 读列，按存在的列拼 SQL；认不出就明确失败并报真实列名，绝不「假装写成功」 |
| 真机验证 | **已端到端跑通**（2026-11，HONOR · MagicOS 11 / Android 17）：短信 → 提取 → `su sqlite3` 写 `task.db/Tasks` → 日历待办可见。表结构、完成列、文件夹/账号归属全部按真机行对齐 |

---

## 一、为什么「捕获侧」几乎不用改

这个模块的短信捕获是分层冗余的（见 `docs/14-hooks.md`）：

```
S1  InboundSmsHandler$SmsBroadcastReceiver   （framework，AOSP 组件）
S2–S7 短信 App 的接收器 / Service             （厂商定制，类名会变）
S8 SmsProvider.insert / bulkInsert           （AOSP 组件，唯一必经的入库点）
```

ColorOS 的适配记录（`docs/17-coloros-adaptation.md`）已经证明：**真正稳的是 S8**，
厂商侧的 S2–S7 在换 ROM 后基本都会「类找不到」然后自动跳过，不影响功能。
荣耀同理——它换的是「信息」App 的包名（`com.android.mms` → `com.hihonor.mms`），
不是短信入库的机制。

所以捕获侧只做了两件事：

1. `XposedEntry` 里把 `com.hihonor.mms` 当成短信 App 进程，挂一遍候选接收器
   （`Honor.MMS_RECEIVER_CANDIDATES`，两族类名各试一遍，找不到就跳过）；
2. `CallerPolicy` 的白名单加上 `com.hihonor.mms` / `com.huawei.mms`，
   否则荣耀的「信息」就算抓到了，跨进程唤醒模块也会被拒。

---

## 二、写入侧：为什么是两条路

荣耀的「待办」在**日历**里（日历 → 待办），备忘录的待办已经迁进日历——
这和 ColorOS 16 是同一个产品决策（见 `docs/17` 第〇节）。

但「库在哪、表叫什么、完成列是哪个」这三件事，荣耀没有公开文档，
维护者也没有样本。硬写一套 SQL 的结果只有两种：prepare 阶段报
`no such column`（写不进去），或者写进去了但日历不认（字段语义猜错）。
所以 `honor_todo` 设计成两条路，先软后硬：

### 路径 1：CalendarProvider（首选，不需要 root）

```java
ContentValues v = new ContentValues();
v.put("calendar_id", <第一个日历>);
v.put("title", content);
v.put("description", content);
v.put("completed", 0);
cr.insert(Uri.parse("content://<authority>/tasks"), v);
```

- **不猜字段**：由系统自己维护 `_sync_*`、账号、`calendar_id` 的关联；
- **不需要 root**：只要 `READ_CALENDAR` / `WRITE_CALENDAR`；
- **authority 逐个试**：`com.android.calendar` → `com.hihonor.calendar` → `com.huawei.calendar`。
  注意 `CalendarContract.Tasks` 是 `@SystemApi`，公开 SDK 里没有这个类，
  所以只能拼 Uri 字符串（写 `CalendarContract.Tasks.CONTENT_URI` 在 Gradle 里直接编译不过——
  这一条是这次编译验证时踩出来的，见第五节）。

### 路径 2：root 直写 `calendar.db`（兜底）

没给日历权限、或 Provider 在荣耀上被裁掉时走这条：`su` + 模块自带的 sqlite3，
SQL 由 `HonorSql` 按**实际表结构**生成。

### 两条都失败

返回 `false`，把原因写进 `HonorWriter.lastDiag`，体检里能看到。
**不会**出现「退出码 0、其实没写」的情况——这正是下面这条设计的由来。

---

## 三、表结构自适应：`HonorSql` 怎么工作

```java
Schema s = HonorSql.parse(  // PRAGMA table_info(Tasks) + sqlite_master 的表名清单
        runner.run(HonorSql.PROBE_SQL));
String sql = HonorSql.insertSql(s, content, "com.hihonor.calendar");
//  null = 没有任何能装正文的列 → 明确失败
```

拼列的顺序与规则：

| 类别 | 处理 |
|---|---|
| 正文 | 存在的都填：`content` / `title` / `text` / `description` / `plain_text`（避免「日历读 title、我们写 content」的错位） |
| 身份 | `local_id` / `global_id` / `local_global_id` → SQL 内联 UUID（ColorOS 那套是文本主键） |
| 挂靠 | `calendar_id` → 从 `Calendars` 取第一个；没有 `Calendars` 表就写 `1` |
| 状态 | `completed=0`、`deleted=0`、`dirty=1`、`_sync_dirty=1`（有则填） |
| 时间 | `create_time` / `update_time` / `sort_time` / `dtstart` / `due` / `_sync_time` → 毫秒时间戳 |

标记已取件按列自适应：有 `finish_time` 就写时间戳（ColorOS 系），
有 `completed` 就置 1（AOSP 系），只有 `status` 就置 2。

**关键一条**：`insertSql` 末尾带

```sql
SELECT CASE WHEN changes() > 0 THEN 'PICKUP_OK' ELSE 'PICKUP_NOOP' END;
```

`HonorWriter` 只认 `PICKUP_OK`。SQLite 对「INSERT 被 WHERE 挡掉」是不报错的，
没有这个标记，一次空转会被当成成功，去重指纹照常落盘，这条取件码就永远写不进去了。

---

## 四、代码改动点

| 文件 | 改动 |
|---|---|
| `Honor.java`（新） | 机型识别（`Build.MANUFACTURER/BRAND` + `ro.build.version.magic` + `ro.build.version.emui` 回落）、包名常量、短信接收器候选、`calendar.db` 路径、日历权限申请 |
| `HonorSql.java`（新） | 纯 Java 的表结构自适应 SQL 生成器（无 Android 依赖，可离线单测） |
| `HonorWriter.java`（新） | 两条写入路径 + 去重查询 + 体检摘要；`Runner` 接口让模块进程与系统进程共用 |
| `NotesBackend.java` | 新后端 `honor_todo`：默认后端判定、可选目标、账号名（荣耀账号）、库路径、目标 App 名；`insertSqlOf/markDoneSqlOf/existsSqlOf` 对荣耀**抛异常**（必须经 HonorWriter，防止有人误用静态模板造成静默空转） |
| `TodoWriter.java` | `writeTodoFor` / `writeTodo` / `markDoneAll` 分流到 `HonorWriter` |
| `SystemDirectWriter.java` | 冻结兜底通道支持荣耀（`HonorWriter.writeWithRunner`） |
| `XposedEntry.java` | `com.hihonor.mms` 进程 → `hookHonorMms`（候选接收器 + `handleSmsMessage`） |
| `CallerPolicy.java` | 白名单加 `com.hihonor.mms` / `com.huawei.mms` |
| `Repair.java` | 荣耀侧作用域要求改成 `com.android.phone` + `com.hihonor.mms`（否则 `com.android.mms` 永远「缺一项」） |
| `Diagnostics.java` | 报告加 MagicOS 版本 / 待办库路径；新增体检项「荣耀待办库」把 `Tasks` 真实列名写进报告 |
| `LauncherActivity.java` | 环境标签、写入目标文案、冻结直写开关对荣耀可见、首次启动申请日历权限 |
| `AndroidManifest.xml` | `READ/WRITE_CALENDAR` 权限、`<queries>` 加三个 `com.hihonor.*` 包 |
| `res/values/arrays.xml` | 推荐作用域加 `com.hihonor.mms` |
| `test/HonorSqlTest.java`（新） | 32 项离线断言（四种表结构 + 拒绝路径 + 转义 + 探针解析） |
| `tools/honor_sql_check.py`（新） | 用真实 sqlite 执行生成的 SQL，逐条断言 |
| `tools/device-probe/honor_probe.sh`（新） | 真机侦察：包名、接收器、Provider、库路径、表结构、样本行 |

---

## 五、已经验证过的部分（离线）

1. **整包编译通过**：用 Android 34 的 `android.jar` + `androidx.room` +
   Xposed API 82 编译 `app/src` 全部 67 个源文件 → **0 error**（119 个 class）。
   这一步抓到了 `CalendarContract.Tasks` 不是公开 API 的问题，
   把 Provider 路径改成了 Uri 字符串 + authority 候选。
2. **`test/HonorSqlTest`：32/32 通过**
   （AOSP 系 / ColorOS 系 / 荣耀混合 / 缺正文列 / 缺 Tasks 表 / 转义 / 探针解析）。
3. **`tools/honor_sql_check.py`：全部通过** —— 用真实 sqlite 建四种合成表结构，
   执行 `HonorSql` 生成的 SQL，断言：恰好写 1 行、正文含引号/百分号/下划线仍然完整、
   `changes()` 标记为 `PICKUP_OK`、完成后 `finish_time` / `completed` 被正确写入、
   取件码里的 `%` `_` 不会被当通配符。

> 合成的表结构代表「可能遇到的形状」，**不代表荣耀真机就是这样**。
> 真机形状请用第六节的脚本导出。

---

## 六、真机确认结果（原三条待确认，已全部落定）

| 原待确认项 | 真机结论 |
|---|---|
| 待办库在哪 | **`databases/task.db`**，表 `Tasks`（`calendar.db` 是日历事件库，留作第二候选） |
| 完成语义 | **`complete` 0/1** + `date_completed` 时间戳；`completed` / `finish_time` 都不存在 |
| 短信接收器类名 | `com.android.mms.transaction.SmsReceiver` / `PrivilegedSmsReceiver` 命中（既有候选已覆盖） |

另外确认的两条（写进实现，见第九～十一节）：

- `Tasks.uuid` 是 `TEXT NOT NULL` 且**没有默认值**，必须自己生成；
- 待办 Provider 的 authority 是 **`com.android.calendar.task`**，`insert` 内部会
  `notifyChange` —— 桌面小组件能不能实时刷新，取决于有没有走它。

### 真机验证步骤

```bash
# 0) 装模块、勾作用域（android / com.android.phone / com.hihonor.mms /
#    com.android.providers.telephony）、重启手机、打开一次 App

# 1) 侦察：先在「日历 → 待办」手动建一条「测试待办AAA」
adb push tools/device-probe/honor_probe.sh /data/local/tmp/
adb shell su -c "sh /data/local/tmp/honor_probe.sh" > honor-probe.txt

# 2) 端到端：注一条假短信（不需要真的收快递）
adb shell su -c 'content insert --uri content://sms/inbox \
  --bind address:s:10690000 --bind body:s:"【菜鸟驿站】您的包裹已到，取件码 5-6-8888"'

# 3) 看三处：模块首页「待取件」、日历「待办」、通知栏

# 4) 已取件：点通知上的 ✓，回到日历看那条待办是否变成已完成

# 5) 体检：首页状态卡 → 点击体检 → 把「荣耀待办库」那一行贴回来
```

---

## 七、降级与回滚

- **不想用系统待办**：设置里把「系统待办」关掉，只留「软件内部记录」——
  取件码照常显示、桌面小组件照常用，不碰日历。
- **日历权限被拒**：自动回落 root 直写，功能不变，只是每次写入多一次 `su`。
- **两条路都失败**：首页会显示写入失败原因；体检「荣耀待办库」会列出真实表结构。
  这时不需要改代码——把那一行发出来，改的是 `HonorSql` 的列映射，不是整条链路。

---

## 八、已知风险

- 荣耀大版本可能改 `Tasks` 表结构：自适应逻辑能兜住「加列 / 改列名」，
  兜不住「改成完全不同的存储」（比如另建一个 Provider）——那种情况体检会红，
  需要按第六节重新侦察。
- 直写绕过 Provider，`_sync_dirty` 之类字段如果荣耀有自己的取值约定，
  可能出现「本地看得见、同步不到云端」。要走云同步的话优先用 Provider 路径（给日历权限）。
- 本文所有「预期」都不是实测结论。第一次真机验证后，请把结论回填到本文，
  并把「未验证」标记去掉。


---

## 九、真机反馈（第一台：HONOR 真机 · MagicOS 11 · Android 17）

`tools/device-probe/honor_probe.sh` 在真机上的输出（2026-11-09），逐条对上：

| 项 | 真机结果 | 对代码的影响 |
|---|---|---|
| 设备 | `HONOR` 机型（型号略），Android 17（SDK 37），`MagicOS 11.0.0` | `Honor.isHonor()` 靠 `MANUFACTURER/BRAND=HONOR` 命中；`ro.build.version.magic` 也能取到 |
| 短信 App | `com.hihonor.mms`（另有 `com.android.mms.service`、`com.android.providers.telephony`） | 作用域与 Hook 入口按此包名 |
| 短信接收器 | `com.android.mms.transaction.SmsReceiver`、`PrivilegedSmsReceiver`、`com.hihonor.rcs.transaction.RcsReceiver`… | 候选表里前两个已命中；`RcsReceiver` 是荣耀新增的 RCS 通道，暂不挂（S8 仍然兜底） |
| 待办库 | `databases/` 下同时有 **`task.db`**（90KB，当天还在写）和 `calendar.db`（192KB） | **`dbPathOf()` 改成 task.db**，calendar.db 作为第二候选 |
| Provider | `content://com.android.calendar/tasks` → `Unknown URL`；真正的待办 Provider 是 `com.android.calendar.task`（`TaskProvider`，只要求 READ/WRITE_CALENDAR） | 写入改走这个 authority；它的 `insert` 内部会 `notifyChange`，桌面小组件才能实时刷新 |
| 备忘录 | `com.hihonor.notepad/databases/note.db` | 暂不适配（待办已迁日历），留作以后 |
| 模块状态 | `/data/data/.../files/rt/` 不存在 | 说明当时还没装模块，所以表结构那几段是空的——装上 APK 后再跑一次即可补齐 |

### 这一轮因此改了什么

1. `Honor.DB_TASK` 新增，`todoDbCandidates()` = `{task.db, calendar.db}`；
   `NotesBackend.dbPathOf(honor_todo)` 指向 task.db。
2. `HonorSql` 的探针从「只读 Tasks」改成「先列全部表名，再对 `Tasks/task/tasks/Task/todo_task/todo/todos`
   逐个 PRAGMA」，取第一个「有正文列」的表——真机表名到底叫什么都无所谓。
3. `HonorWriter` 按库候选顺序试写：哪个库先回 `PICKUP_OK` 就算成功，
   失败的库把原因（表不存在 / 没有正文列 / su 不可用）拼进 `lastDiag`。
4. 体检的「待办库访问」在荣耀上走 `checkHonorDb`：把两个库的真实表/列都打出来，
   而不是拿一个固定表名去 count。

### 装上 APK 后请补一次

```bash
adb shell su -c "sh /data/local/tmp/honor_probe.sh" > honor-probe2.txt
```

这次 `files/rt/sqlite3` 已经部署好了，脚本会把 `task.db` 的表名和列名打全——
拿到这段就能把「表名 + 完成列」从「自适应」升级成「确定」，顺便确认
`task.db` 里到底是不是 `task` 表、完成列是 `completed` 还是 `finish_time`。


---

## 十、真机验证结果（2026-11-10，第二台反馈：写入成功）

### 落地证据

从手机只读拉取 `task.db` 后确认，模块写入的行：

| 字段 | 值 |
|---|---|
| `_id` | 13（追加在 12 条官方待办之后） |
| `uuid` | `3ddf5ef7$6b06$4c62$9f4a$724844c925f8`（`8$4$4$4$12` 形状，与官方一致） |
| `title` / `body` | `取件码 99-9-7686｜菜鸟驿站｜XX小区快递站｜2026-11-10 09:26` |
| `complete` | 0 |
| `folder_uuid` | `5546611e$...`（`default_unclassified`） |
| `account_uuid` | `default_account_uuid` |
| `Instances` | 自动补了一行；随后日历 App 把它规范化成 `startMinute=480`，说明它确实被 App 读走了 |

### 真机 `Tasks` 结构（节选，写死不得）

```sql
CREATE TABLE Tasks (
  _id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  uuid TEXT NOT NULL,              -- 必须自己生成，否则 NOT NULL constraint failed
  title TEXT, body TEXT, html_content TEXT, search_content TEXT,
  complete INTEGER NOT NULL DEFAULT 0,   -- 完成列叫 complete，不是 completed
  folder_uuid TEXT, account_uuid TEXT,   -- 不挂 Folder/Account 就不显示
  dirty INTEGER, delete_flag INTEGER, delete_time INTEGER,
  create_time INTEGER, modify_time INTEGER, due_date INTEGER, date_completed INTEGER,
  isAllDay INTEGER, reminder_type INTEGER, message TEXT, ...
);
```

### 这一轮改出来的两条经验

1. **不要把「非 0 退出」一律翻译成「su 不可用」**。第一版就是这么写的，
   真正的 `NOT NULL constraint failed: Tasks.uuid` 被吞掉，白查一轮。
   现在 `HonorWriter` 失败时把 sqlite 原文带进界面提示。
2. **表结构自适应只能兜住「列有无」，兜不住「NOT NULL 无默认」**。
   `uuid` 这类列必须从真机样本里学到，靠 `PRAGMA table_info` 的 notnull 标志也能推，
   但值怎么生成（`$` 分隔 vs `-`）只能看样本。

### 仍未验证

- **已取件（点 ✓）**：`complete=1` + `date_completed` 的写法已在真机库副本上验过，
  但没在真机上点过一次——这是下一个要确认的点。
- **文件夹归属**：现在落在 `default_unclassified`；官方短信待办落在 `Message` 文件夹。
  想归到一处的话，把 `folder_uuid` 的子查询改成
  `COALESCE((SELECT uuid FROM Folders WHERE display_name='Message' LIMIT 1), (SELECT uuid FROM Folders LIMIT 1))`。


---

## 十一、桌面小组件不刷新：根因与修法（2026-11-10 真机反馈）

### 现象

- 模块写入后，日历「待办」里能看到新条目；
- 但桌面上的待办小组件不动，直到用户手动在日历里新建一条待办，小组件才「一起」把新条目刷出来；
- 勾选已取件同理：日历里变成已完成，小组件那一行还挂着。

### 根因

模块是 `su sqlite3` 直写 `task.db`，**绕过了 Provider**。而刷新是靠数据变更通知驱动的：

- 荣耀其实有专门的待办 Provider：
  `com.android.providers.calendar/.task.db.TaskProvider`，
  **authority 是 `com.android.calendar.task`**（不是 `com.android.calendar`，
  所以之前 `content://com.android.calendar/tasks` 才会报 `Unknown URL`）；
- 它 `exported=true`，读写只要 `READ_CALENDAR` / `WRITE_CALENDAR`；
- 反编译它的 `insert` 可以看到末尾调了 `ContentResolver.notifyChange(...)`，
  另外还会 `insertTaskToMMSearch(taskId)` 把待办写进系统搜索索引；
- 桌面小组件是 `com.hihonor.calendar/.widget.task.TaskWidgetProvider`，
  `updatePeriodMillis=1800000`（30 分钟）——没有变更通知时，它只会等这个周期。

直写数据库不产生任何通知，所以小组件只能等 30 分钟或等一次别的事件。

### 修法

1. **Provider 优先**：`HonorWriter` 改成先往 `content://com.android.calendar.task/tasks`
   插（字段与直写同一套，插完按返回的 Uri / 标题查回来确认），
   成功即由 Provider 自己发通知——日历与小组件同步刷新，顺带进搜索索引。
2. **直写兜底 + 补通知**：没给日历权限或 Provider 拒收时仍走 `su sqlite3`，
   写完显式 `notifyChange(content://com.android.calendar.task/tasks)`，
   把观察者叫醒（小组件、日历 App 都挂在同一个 authority 上）。
3. 已取件同理：`update(...)` 走 Provider，`complete=1 + date_completed + modify_time`。

### 顺带确认

- Provider 的 `query` 对第三方可用（`content query --uri content://com.android.calendar.task/tasks`
  在 shell 下就能读到全部待办），所以去重查询也可以走它，不必每次都 su。


---

## 十二、「短信有记录、待办没有」：去重指纹的副作用（2026-11-10）

### 现象

驿站把同一条短信重发了一次（09:40 与 10:12 各一条，取件码、地点完全相同）：

- 取件记录里多了一条；
- 日历待办里没有新增；
- 而「一键链路测试」每次都正常写入。

### 根因（真机取证）

1. `shared_prefs/dedup.xml` 的 `seen` 里已经有
   `12-3-4567|某驿站（占位）`（09:40 那条写入时加进去的）；
2. `task.db` 里 09:40 那条待办（`_id=15`）已经被删掉（`delete_flag=1`）；
3. 10:12 重发时命中指纹 → `dedup skip` → **跳过写入**；
4. 本地记录是另一条独立路径（Provider 调用里先落 Room 再写待办），所以照样 +1。

链路测试每次生成新的随机码，永远不命中指纹，所以一直是好的。

### 修法

命中指纹时不再无条件跳过，而是先问一句「目标库里还有没有**未删除**的待办」：

- 有（无论完成与否）→ 仍然跳过（避免重复打扰）；
- 全都被删掉 → 重新写一条，并把指纹续上。

荣耀侧这句用 Provider 查询（`title LIKE ? AND delete_flag=0`），不需要 su；
其他 ROM 回落到原来的只读 count 探针。正常新取件码完全不经过这条路径。

### 结论

这条与荣耀无关，是**跨 ROM 的通用缺陷**：指纹只记「写过」，不记「还在不在」。
放在荣耀这轮一起修，是因为荣耀的删除语义（`delete_flag`）让它在真机上第一次显形。
