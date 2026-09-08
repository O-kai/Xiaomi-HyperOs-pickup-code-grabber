# ColorOS 16 适配：侦察与验证记录（最终版：日历待办）

> **来源说明**：本文档由社区开发者 [Vv-Ww](https://github.com/Vv-Ww) 在其
> [ColorOS 二改版仓库](https://github.com/Vv-Ww/pickup-code-grabber-coloros) 中撰写
> （`docs/16-coloros-adaptation.md`），经作者同意后移植到上游本仓库（`docs/17`）。
> v2.7.0 的多 ROM 支持正是基于这份逆向侦察与端到端验证记录实现的。
> **ColorOS 侧适配问题请优先携带诊断报告到上游 Issues 反馈**（上游已整合该能力）。

> 日期：2026-09-08 ｜ 实测设备：一加 9 Pro（LE2120）· ColorOS 16（LE2120_16.0.8.301 CN01）· Android 16 · KernelSU + LSPosed
> 目的：记录把本模块从小米 HyperOS 适配到 ColorOS 16 的全过程——目标库侦察、字段验证、端到端实测、代码改动点。后续 ColorOS 版本更新若失效，按本文方法复查。

## 〇、结论速览

ColorOS 16 的「待办」在**日历 App** 里（便签的待办已整体迁移）。模块在 ColorOS 上的正确写法：
**短信捕获逻辑零改动**，把写入目标从小米笔记 `todo.db` 换成**系统 CalendarProvider 的 `tasks.db`（Tasks 表）**，
用 `su -M sqlite3` 直写；「已取件」= 给对应待办写 `finish_time`。
经真实短信注入端到端验证：新待办出现在日历「待办」列表，勾选完成正常。

## 一、为什么适配工作集中在"写入侧"

- **短信捕获侧（S1–S8）基本通用**：主通道 S8 Hook 的 `SmsProvider.insert/bulkInsert`（`com.android.providers.telephony`）是 AOSP 标准组件，ColorOS 上同样存在。S4–S7（小米定制 Receiver）与 `MiuiTelephonyProviderImpl` 在 ColorOS 上类找不到即自动跳过，不影响主功能。
- **写入侧是硬绑定**：原实现 `su -M sqlite3` 直写小米笔记私有库 `/data/user/0/com.miui.notes/databases/todo.db`。ColorOS 无此 App，且笔记/待办体系不同。

## 二、真机侦察结论（ColorOS 16）

### 1. 待办真正在日历：系统 CalendarProvider 的 tasks.db
- 包：`com.android.providers.calendar`（系统应用）
- 库：`/data/data/com.android.providers.calendar/databases/tasks.db`，主表 **`Tasks`**
- 官方 Provider 接口 `content://com.oplus.task/tasks`（含 `/tasks/finish`）**需要平台签名权限 `com.oplus.permission.safe.SECURITY`，第三方 App 无法调用** → 只能 root 直写
- 便签 `com.coloros.note` 的 `nearme_note.db` 里 `todo` 表是**遗留空表**（App 内有预置公告《待办迁移至"日历"》），不可用；便签只剩普通笔记

### 2. Tasks 表字段约定（逆向自日历 App 亲手创建的样本：无提醒快捷待办）
样本：「测试待办AAA」（未完成）+「测试待办BBB」（已完成），关键取值：

| 字段 | 未完成 | 已完成（BBB） | 说明 |
|---|---|---|---|
| `local_id` / `global_id` / `local_global_id` | UUID 文本 | UUID | local_global_id = local_id |
| `dirty` | 1 | 1 | |
| `content` | 待办文本 | 待办文本 | `title` 恒 NULL |
| `allDay` | 1 | 1 | |
| `mutators` | `com.coloros.calendar` | 同 | |
| `create_package` | `com.coloros.calendar_10325_7e18a2f3…(64hex)` | 同 | App 包名+版本号+哈希 |
| `create_time` / `update_time` / `sort_time` | 毫秒时间戳 | 同 | sort_time≈create_time |
| `finish_time` | NULL | **已填** | **完成标志 = finish_time 非空** |
| `color` | 2 | 2 | |
| `timezone` | `UTC` | 同 | 无提醒快捷待办 |
| `sys_version` | 1 | 1 | |
| `completed` | 0 | **0** | **不使用**（完成看 finish_time） |
| `deleted` | 0 | 0 | |

## 三、直写 SQL 模板（真机验证）

新增待办：
```sql
INSERT INTO Tasks (local_id, global_id, local_global_id, dirty, content, allDay, star,
  mutators, create_package, create_time, update_time, sort_time, color, timezone,
  sys_version, force_reminder, canPartiallyUpdate, deleted)
VALUES ('<uuid>', '<uuid>', '<uuid>', 1, '<content>', 1, 0,
  'com.coloros.calendar',
  'com.coloros.calendar_10325_7e18a2f30df7f24934d856fca0ba721bc94c728898f30cccbbab398606c921ac',
  <now_ms>, <now_ms>, <now_ms>, 2, 'UTC', 1, 0, 0, 0);
```

标记已完成（对应「已取件」）：
```sql
UPDATE Tasks SET finish_time = <now_ms>, update_time = <now_ms>
WHERE deleted = 0 AND finish_time IS NULL AND content LIKE '%<code>%';
```

### 直写要点
- KernelSU `u:r:ksu:s0` 域下 root 直写无需任何 chmod；`databases` 目录 chmod 会被 SELinux 拒绝（无害）
- 设备无自带 sqlite3 → 沿用 APK 内置 arm64 套件，部署到 `/data/local/tmp/pickup_sqlite/`（与小米侧一致）
- 直写后**日历 App 重开即显示新待办**（实测无需清 provider 缓存）；写入目标 App 无需加入 LSPosed 作用域（模块没有针对它的 Hook）

### 便签置顶笔记（备用后端，非默认）
如需"写入便签为置顶笔记"而非日历待办，用 `NotesBackend.override("coloros_note")`。目标：
`com.coloros.note` → `nearme_note.db` 的 `rich_notes` 表（`top_time` 置顶，列表标题靠 `summary_title`），
字段模板见 git 历史中本文早期版本，实测有效。

## 四、代码改动点

| 文件 | 改动 |
|---|---|
| `NotesBackend.java`（新增） | 后端抽象：`xiaomi`（todo.db）/ `coloros_todo`（tasks.db，默认）/ `coloros_note`（便签，备用）。自动识别 = 小米 ROM/小米笔记存在 → xiaomi，否则 coloros_todo。集中包名、DB 路径、chmod、INSERT/UPDATE/count 探针 SQL 与目标 App 名 |
| `TodoWriter.java` | `writeTodo` 改走 `NotesBackend.insertSql`；`sqlEscapePub` 供后端组 SQL |
| `TodoProvider.java` / `LauncherActivity.java` | 已取件 SQL、查看待办跳转包名改走 `NotesBackend` |
| `Notifier.java` | 「查看待办」跳转包名动态化（ColorOS → com.coloros.calendar） |
| `Repair.java` | 作用域必需项收敛为 `android/phone/mms`（+可选 providers.telephony）——笔记/日历 App 非 Hook 目标，不再要求勾选 |
| `Diagnostics.java` | 探针表名/文案按后端；报告增加 ColorOS 版本、写入后端行 |
| `AndroidManifest.xml` | 追加 `<queries>`（com.coloros.note / com.miui.notes）保证包检测准确；描述文案更新 |
| `res/values/arrays.xml` | 推荐作用域并列两个笔记 App（LSPosed 自动忽略未安装者，仅提示用） |
| `XposedEntry.java` 等 Hook 侧 | **零改动**（找不到的小米类自动跳过） |

## 五、验证记录（端到端，LSPosed + KernelSU）

1. **直写验证**：root sqlite3 INSERT「📦 取件码 5-6-8888｜菜鸟驿站」→ 日历待办页**显示该待办** ✓
2. **全链路**：root `content insert --uri content://sms/inbox` 注入「【菜鸟驿站】…取件码：5-6-8888」→
   S8 Hook（com.android.providers.telephony）触发 → Provider 唤醒模块 App →
   `NotesBackend` 识别 coloros_todo → su sqlite3 写 tasks.db → 日历待办页出现新待办 ✓；
   通知弹出 ✓；重复事件去重 ✓
3. **已取件**：模拟点通知按钮 → Tasks 行 `finish_time` 写入（UI 勾选完成）✓
4. **兜底验证**（更早版本，便签方案）：rich_notes 置顶插入/标题修复/取消置顶同理通过（此路保留为备用后端）
5. **踩坑记录**：
   - 新装模块 App 未打开过（`stopped=true`）时 Provider 解析失败（报 `Unknown authority`）→ 首次使用先打开一次 App
   - Android 11+ 包可见性：App 内 `getPackageInfo("com.coloros.note")` 需 manifest `<queries>`，否则探测不到目标包
   - adb 拉取 WAL 库必须 `exec-out`（`shell` 的换行转换会损坏文件）；执行带参数 `su -c` 用单引号整体包裹
   - LSPosed 作用域里勾「system」无效；底部「Android 系统（android）」才是框架
   - 用 `content` 命令插数据：`--bind` 须写成 `列名:类型:值`（无空格分隔）

## 六、后续风险与复查方法

- ColorOS 大版本可能改 `Tasks` 表结构/字段语义：体检「待办库访问」会报 `no such table` → 按下法复查
- `create_package` 的 `<版本号>_<64hex>` 后缀为逆向值（疑与签名/设备相关）：换机/换系统版本后如日历不显示新待办，用日历 App 手建一条待办对比该字段
- 排查命令速查（root）：
  ```
  adb exec-out su -c "cat /data/data/com.android.providers.calendar/databases/tasks.db" > t.db
  adb exec-out su -c "cat /data/data/com.android.providers.calendar/databases/tasks.db-wal" > t.db-wal
  # 同目录放置后 sqlite3 打开即自动回放 WAL
  ```
