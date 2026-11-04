# 09 · 待办写入路径验证报告（2026-09-03 实机实验）

> 实验目标：确认"向小米笔记写入待办"的可行技术路线。
> 过程：备份 → 直写 SQL → 验证 → 用户确认 App 可见 → 删除 → 完整性检查。
> 结果：**路线 B（root 直写 todo.db）端到端验证通过。**

## 一、实验过程记录

| 步骤 | 操作 | 结果 |
|---|---|---|
| 1 | 备份 `/data/user/0/com.miui.notes/databases/todo.db` → `/data/local/tmp/todo-db-backup-20260903-044104.db` | ✅ |
| 2 | 通过 Termux sqlite3（root）执行 INSERT（内容：【测试】取件码 22-2-3579｜菜鸟驿站｜写入验证-请忽略） | ✅ id=137 |
| 3 | 数据库校验 | ✅ `is_finish=0, list_type=0, custom_sort_id=39845888(=旧最大+0x100000), create_time=现在毫秒` |
| 4 | 用户打开小米笔记 → 待办页 | ✅ **测试条目出现在列表顶部**（无需重启 App） |
| 5 | DELETE id=137 + integrity_check | ✅ 总数恢复 37，`ok` |

## 二、验证成立的关键事实

1. **todo.db 直写可行且立即对 UI 生效**：写入后 App 待办列表刷新即可见，无需重启/清缓存。
2. **排序字段必须维护**：`custom_sort_id` = 当前最大值 + `1048576`（0x100000），新条目排在顶部；
   若置 0 则可能落入列表尾部或异常位置。（现有数据验证：每条 step=0x100000，最新值最大。）
3. **最小必填字段集**（实测可用）：
   `content`、`plain_text`（与 content 相同）、`is_finish=0`、`list_type=0`、`type/category/folder_id/source/input_type/remind_type/priority/hide_type/sort_id/local_status/server_status/words_count=0`、
   `custom_sort_id`、`version=1`、`create_time`/`last_modified_time`（毫秒）。
4. **App 显示路径确认**：App 内部查询 `SELECT * FROM todo WHERE is_finish = 0 AND local_status != 3`
   ——待办页直接读 todo.db，这就是为什么直写能立刻显示。
5. **Provider 外接口不可用**：`content://com.miui.todo.provider/todo/out_insert`、`.../out_finish`、
   `.../outer`、`.../private`、`.../pri_sync` 均存在，但外部（shell/root）调用全部静默无效果
   （未见报错、无写入）——该 Provider 显然只对 App 自身/签名方有效。
6. **新数据模型（旁路）**：dex 显示 App 还有新模型：主库 `documents` 表（`noteType='todo'`）+
   `todo_mappings` + `legacy_todos` 镜像表；"待办页"当前读的是 legacy todo.db（第 4 点 SQL），
   新模型用于 AI 卡片等场景。写入时只动 legacy 表即可满足待办页显示。

## 三、写入路线最终决策

| 路线 | 结论 |
|---|---|
| **B. root 直写 todo.db（SQLite）** | ✅ **已验证可行**，作为产品主实现：模块捕获后由模块自身进程 `su -c` 执行 INSERT/MARK_FINISHED |
| A. Hook com.miui.notes 进程内写入 | 保留为后续增强（云同步语义更完整时再上）；当前版本不依赖 |
| C. Provider 外部调用 | ❌ 实测不可用，废弃 |

风险与应对：
- `su` 授权：模块 App 首次执行需在 Magisk Superuser 弹窗中授权"取件码助手"（一次性）。
- 云同步：直写行 `sync_id/server_status=0`，与用户现有待办一致；云同步行为未在本次实验验证
  （若发现云端不同步，再评估路线 A 或接入 sync 字段）。
- App 缓存：本次验证证明列表在下次读取时重新查库；用户手动刷新即最新。

## 四、对整体方案的影响

- 待办写入环节从"最大风险点"降级为**已验证可用**：P4 阶段工作 = 在模块里封装
  `TodoWriter`（构造 INSERT/UPDATE，按 定制排序 与 去重规则），不再需要探索式攻坚。
- 去重：直写前先 `SELECT` 待办库中是否已存在同"码+地点"条目（当前未完成态），存在则跳过。
- 标记完成（已取件）：UPDATE `is_finish=1, mark_finish_time=now`（App 内勾选同理）。

## 五、工具链沉淀（后续都用得上）
- 手机端可执行：`su -c '/data/data/com.termux/files/usr/bin/sqlite3 <db> < <sql文件>'`（v3.53.4）
- PC 端分析：`tools\platform-tools\sqlite3.exe` + adb（`tools\platform-tools\adb.exe`）
- 便签 App APK 已留存：`recon\notes-base.apk`（dex 字符串扫描方法有效，备用）

## 六、堆栈式置顶验证（用户确认 2026-09-03）

**用户疑问**："新待办置顶、旧的被压下去"的自然堆栈行为，而不是"硬塞大常数"。

**实证结论**：该行为**就是小米笔记的原生算法**。用户现存待办的 `custom_sort_id` 按
+1048576（0x100000）严格递增、最新值最大（33554432 → 38797312），即系统对每条新待办
执行"当前最大值 + 0x100000"。模块方案 1:1 复刻该算法，而非另行发明排序。

**实机演示（双条堆栈）**：
1. 插入【测试A】取件码 11-1-1111 → custom_sort_id=39845888
2. 插入【测试B】取件码 22-2-2222 → custom_sort_id=40894464（=A+0x100000）
3. 用户打开小米笔记待办页确认：**【测试B】在顶部、【测试A】第二** ✅ → "新压旧"堆栈成立
4. 删除两条演示数据，总数恢复 37，integrity_check=ok ✅

**结论**：维持 `custom_sort_id = MAX + 0x100000` 方案，即"回归原始系统级逻辑"。
