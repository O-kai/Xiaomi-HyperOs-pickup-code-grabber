# Stage 2 报告：本地取件库、待取件入口、桌面小组件

> 日期：2026-10-30
> 分支：`release/4.0`
> 版本：**3.8.0-internal / 380**
> 状态：本地库/首页/列表/小组件代码完成；小组件视觉固定待真机添加验证

## 一、完成项

### 1. 本地取件库（Room）

- 新增 `PickupRecord` / `PickupDao` / `PickupDatabase` / `PickupRepository`。
- 表 `pickup_records`：码、来源、地点、收到时间、状态、`event_hash`（唯一）、同步状态、`is_test`。
- `event_hash` 去重：同一短信经多个 Hook 点重复上报只落一条。
- 活跃记录按 `(code, place)` 去重，重复短信只 touch `last_seen_at`。
- 默认不存短信正文；只存结构化取件信息。
- Hook 进程不引用 Room；本地库只在 App 进程使用。

### 2. Provider 接入

- `TodoProvider.onSms` 在调用 `TodoWriter.handle` 之前先写本地库。
- 黑名单短信不落本地。
- 本地首次记录成功、但系统待办已存在/写入失败时，仍发通知（本地库是事实源）。
- 链路测试通过 `test=true` 标记，不进入待取件/小组件。

### 3. 首页待取件入口

- 首页第一张卡改为“待取件”，显示最多 3 条：码、地点、已取。
- 点击码复制；点“已取”同时更新本地库 + 小米笔记。
- “查看全部”进入 `PickupListActivity`。
- 无记录时显示“暂无待取件 · 收到短信后会自动出现”。

### 4. 本地取件列表

- `PickupListActivity`：最近记录、状态、复制、已取件、添加到桌面。
- 已取件统一走 `PickupActions`：本地 `PICKED_UP` + `NotesBackend.markDoneSql`。
- 系统待办同步失败时提示“已在本机标记，系统待办同步失败”。

### 5. 桌面小组件（代码完成）

- `PickupWidgetProvider`：4×2/4×4 固定 3 行，不使用 collection API。
- 显示待取件码、地点、已取按钮；点码复制、点行/标题打开列表。
- `updatePeriodMillis=0`，本地库变化时事件驱动刷新。
- `WidgetActionActivity` exported=false；`PendingIntent` 使用唯一 data URI + IMMUTABLE。
- `requestPinAppWidget` 入口已加；HyperOS 上系统未弹固定对话框，需后续手动添加验证或改用引导。

## 二、真机验证

设备：REDMI K90 Pro Max / HyperOS 4.0 / Android 17。

| 项 | 结果 |
|---|---|
| 安装 3.8.0-internal(380) | PASS，覆盖 3.6.0 保留数据 |
| 真实 `SmsProvider.insert` | PASS：测试短信 `6-6-6660` → 本地库 1 条 + 小米笔记 1 条 + 通知 |
| 重复 Hook | PASS：`dedup skip`，本地库仍 1 条 |
| 首页待取件卡 | PASS：显示码/地点/已取/查看全部 |
| 列表页 | PASS：显示待取、复制、已取件 |
| 已取件一致性 | PASS：本地 `PICKED_UP`，小米笔记 `is_finish=1` |
| 测试数据清理 | PASS：测试待办、测试短信、本地测试记录均已删除 |

证据：

- `artifacts/stage2-home.png`
- `artifacts/stage2-home-empty.png`
- `artifacts/stage2-list.png`
- `artifacts/stage2-list-done.png`
- `artifacts/stage2-widget-pin.png`

## 三、构建产物

- 路径：`artifacts/pickup-code-grabber-3.8.0-internal-release.apk`
- 体积：5.6 MB
- SHA-256：`7e00a94fe6ad1ef396ab3df1075fa863bf8da7b721feb20f2f55afccef174603`
- 签名：`CN=PickupCodeGrabber`（生产证书）
- 当前设备安装：3.8.0-internal / 380

## 四、未完成 / 风险

1. **小组件视觉未固定验证**：HyperOS 未弹 `requestPinAppWidget` 对话框；需要手动长按桌面添加，确认 3 行布局/点击/刷新。
2. **Room 进入 App APK**：release 从 3.4MB 增至 5.6MB；后续 Compose/R8 会继续增加，需要体积预算。
3. **SystemDirectWriter 兜底不写本地库**：冻结兜底仍只写系统待办；后续需要事件日志/回填，避免本地库与系统待办不一致。
4. **本地库未加密**：依赖 Android 应用私有目录 + 文件级加密；默认不存正文，风险可控。
5. **Stage 1 的 `:core`/`:contract` 分层尚未完成**：先交付了用户可见的本地闭环；分层仍是 4.0 发布前必做项。
6. **列表页是 Java MVP**：Compose 双模式/深色/边到边在 Stage 3 完成。

## 五、下一步

1. 真机手动添加小组件，验证 3 行布局、复制、已取件、刷新。
2. 进入 Stage 3：Compose 双模式 UI；同时补 `:core`/`:contract` 分层与规则单源。
3. 继续保持“本地库事实源 + 系统待办写穿”的一致性。
