# 更新日志（Changelog）

> 版本策略：对外发布延续内部版本号。完整生命周期故事见 [docs/HISTORY.md](docs/HISTORY.md)。

## [2.1.1] - 2026-09-03

- 黑名单设置改版：预览当前关键词 → 「修改」→ 编辑保存，交互更清晰；
- 一键测试改为**随机取件码**（`99-9-XXXX`），永不撞上去重记录，可反复自测；
- 状态卡新增「写入位置」提示；版本号与界面文案统一为 v2.1.1；
- 按钮布局修正（独立整行按钮使用 MATCH_PARENT，避免被 weight 布局挤压）；
- **移除桌面小组件与 MarkDoneReceiver**（回归精简；小组件后续版本再评估）。

## [2.1.0] - 2026-09-03

- 待办模板三档：极简（仅取件码）/ 完整（码+来源+地点+时间）/ 自定义占位符模板（`{code}` `{source}` `{place}` `{time}`）；
- 通知增强：点击通知复制取件码 + 打开小米笔记；每条码带「已取件」动作按钮（联动更新待办完成态）；
- 设置页新增「🧪 一键测试」（不消耗短信，注入测试短信验证全链路）；
- 新增黑名单关键词（默认 12306 / 验证码 / 余额 / 充值 / 账单 / 银行 / 优惠券 / 退订）；
- 新增桌面小组件（最近取件码展示，点击复制）—— **注：该功能已于 2.1.1 移除**；
- 设置页启动时引导申请「通知」权限（Android 13+）。

## [2.0.0] - 2026-09-03 · 全面重构（里程碑：端到端实机验证通过）

- **根治历史 Bug**：弃用一切手工 stub，改用官方 `xposed-api-82.jar` 编译，
  `AbstractMethodError` 从根上消除（见 [docs/13-pipeline-log.md](docs/13-pipeline-log.md) 节点 1）；
- **主捕获通道 S8**：Hook `SmsProvider.insert` / `bulkInsert`（短信库写入必经点），
  普通短信与小米网络短信 100% 覆盖；保留 S1–S7 多点冗余兜底；
- **后台唤醒**：Hook 进程经 `ContentResolver.call` → 模块 Provider 处理，
  规避 MIUI 后台冻结 / 进程未启动问题；
- **待办写入**：`su -M`（全局挂载命名空间）+ sqlite3 直写小米笔记 `todo.db`，
  `custom_sort_id = MAX + 0x100000` 实现新码堆栈置顶；解决 su 127（二进制不可执行）、
  su 后打开 DB 失败（挂载命名空间）、目录权限 700 等系列问题；
- **提取引擎重构**：支持「凭 22-2-3579 到…取件」（无关键词锚定）、一条短信多个取件码（簇提取）、
  代码形状校验（横线码 / 字母数字码）；
- **去重**：码 + 地点指纹，防止同码重复写入；
- 新增通知（点击复制）、模块设置页（模板 / 说明 / 版本）、通知权限引导。

## [1.x] - 早期历史版本（存档，不再维护）

- 一代方案：Xposed Hook 短信广播 + NotificationListenerService 通知监听（菜鸟 / 丰仓 / 京东等 App）；
- 取件码存储于 `/sdcard/Documents/pickup_codes.json`（用户可读）；
- 可选「便签同步」：root 直写小米便签库（按笔记 ID）；
- 迭代 12+ 个版本（v12 前通知监听时代 → v13 图标/同步优化 → v14/v15 手工构建管线 →
  v16 定位 `AbstractMethodError`，未部署）；
- **教训**：手工 stub 构建的模块入口从未被真正调用（签名错误），
  遗留经验即可，代码不再维护（详见 [docs/HISTORY.md](docs/HISTORY.md)）。

[2.1.1]: https://github.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber/releases/tag/v2.1.1
[2.1.0]: https://github.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber/releases/tag/v2.1.0
