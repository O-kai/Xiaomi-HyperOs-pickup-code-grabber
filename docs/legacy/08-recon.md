# 08 · P0 实机侦察报告（2026-09-03 / adb 实机）

> 全部命令为只读操作；数据库仅供本地结构分析。设备通过 USB adb 连接。

## 一、设备与系统环境（实测值)

| 项 | 值 |
|---|---|
| 设备 | REDMI K90 Pro Max（产品名 myron，型号 25102RKBEC） |
| Android | **17（SDK 37）** |
| 系统版本 | **HyperOS OS4.0.0.21.XPMCNXM**（HyperOS 4.0） |
| Root | Magisk（`su -c id` → uid=0，context=u:r:magisk:s0）✅ |
| 框架 | LSPosed（用户确认可用；Manager 包被 HMA 隐藏，pm 查不到属正常） |
| 短信 App | **`com.android.mms`**（另有 com.android.mms.service） |
| 笔记 App | **`com.miui.notes`**（小米笔记） |
| 便签数据 Provider | authority=**`notes`** → `.provider.NotesProvider` |
| **待办数据 Provider** | authority=**`com.miui.todo.provider`** → `com.miui.todo.data.provider.TodoProvider` |
| 其他 Provider | com.miui.notes.action / .cloudservices / AgenticNoteProvider / NoteASRProvider / recording 等（与本项目无关） |

## 二、待办数据库 `todo.db`（路径 /data/user/0/com.miui.notes/databases/todo.db）

- 模式：rollback journal（`todo.db-journal`，无 WAL）—— root 直写 SQLite 无 WAL 坑
- 表：仅 `todo` 一张（+ android_metadata、sqlite_sequence）
- 当前数据：37 行
- **关键列**（INSERT 需重点关注）：
  - `id` INTEGER PK AUTOINCREMENT
  - `content` TEXT（普通待办=纯文本；清单型待办=JSON `{"isExpand":..,"subTodoEntities":[{"content":..,"isFinish":..}],"title":..}`）
  - `is_finish` / `mark_finish_time`（完成状态，勾选即置 1）
  - `create_time` / `last_modified_time`（毫秒时间戳；`last_modified_time` 默认
    `strftime('%s','now')*1000`）
  - `list_type`：**0=普通待办，1=清单型待办**
  - `type` / `category` / `folder_id`：现网数据全部为 0（默认列表）
  - `source`：0 / 1（0=用户手动创建，1=历史行中有 source=1 的样例，语义待确认）
  - `plain_text`、`snippet`、`label`、`priority`、`remind_type` 等：直观语义，新增时用默认值即可

### 字段分布实测

| list_type | type | category | folder_id | source | is_finish | 行数 |
|---|---|---|---|---|---|---|
| 0 | 0 | 0 | 0 | 0 | 0 | 12 |
| 0 | 0 | 0 | 0 | 1 | 0 | 24 |
| 1 | 0 | 0 | 0 | 0 | 1 | 1 |

### 重要发现：id=136 是上一套流程的"取件码档案"

清单型待办（list_type=1，is_finish=1），`content` 为 JSON：
`{"isExpand":false,"subTodoEntities":[{...},{"content":"2026-08-27 菜鸟驿站：16-4-9626, 15-3-2194, 16-3-0906","isFinish":true},...,"title":"📦 快递取件码"}`
— 记录了 2026-07-16 ~ 2026-08-27 的历次取件码（与用户样例 #2 完全吻合），末尾注明
"以上为自动提取，新取件码会追加在下方"。**这是旧形态：全部码挤在一条待办里。**

用户已确认新需求为"**一个取件码 = 一条独立待办**"，与旧档案形态不同：
- 新形态 = `list_type=0` + `content=纯文本`，每条自带勾选框（is_finish），天然对应"已取件"标记。
- 旧档案（id=136）保留不动，不迁移（历史数据不破坏）。

## 三、Provider 可访问性探测（`content` 命令）

- `content query --uri content://com.miui.todo.provider/todo`（shell 用户与 root 均可执行，
  **无 SecurityException**，但返回"No result found"）
- 已探测路径：`/`、`/todo`、`/todo_list`、`/todoitem`、`/items` —— 全部空结果
- 结论：Provider 读权限看起来开放（或 content 命令未触发权限），但**正确的查询 URI 路径
  尚未确定**；"No result found" 也可能是路径不匹配时的静默空游标。写入权限未验证。

## 四、待办写入路线（P0 后更新的决策依据）

| 路线 | 可行性判断 |
|---|---|
| **A. Hook com.miui.notes 进程调用其自身逻辑（首选）** | 类/Provider 已定位（TodoProvider，`com.miui.todo.data.provider` 包）；可在模块内用 app 自己的 Context+ContentResolver 操作；**精准 URI 由"运行时探针"获得：Hook ContentResolver.insert，用户手动建一条待办即记录真实 URI 与 values 映射**。UI 刷新/同步/勾选全正确 |
| B. root sqlite 直写 todo.db（备选） | Schema 已完全明确（见上表，字段默认值已知）；todo.db 无 WAL 风险；风险=App 列表刷新时机（大概率 resume 刷新，待实机验证）与 `source`/`last_modified_time` 语义 |
| C. `content insert`（废弃） | URI 路径未知，先探测后定；写入权限未验证 |

## 五、下一步待办（写权限验证实验）

实验：用 `content insert`（root）以"**测试-写入验证后即删**"的临时条目验证写路径；
- 若 Provider 拒绝写入 → 确定路线 B（sqlite 直写）
- 若写入成功且 App 内可见 → 确定路线 C'（provider 直插，仍属可选路径）
- 实验数据验证后立即删除，不影响用户数据（需用户确认后执行）

## 六、其他侦察结论

- `note.db`（便签库）：表 account/data/note/data_view/note_view/data_call_view/note_call_view
  —— 便签数据模型，与待办无关（本方案只写待办，便签库不动）。
- 系统 Android 17 / HyperOS 4.0 很新：Hook 点（SmsBroadcastReceiver 等）的类名稳定性需在
  P1 实测验证；作用域按 07 文档第四节最小化配置（com.android.phone + com.android.mms +
  system 兜底）。
