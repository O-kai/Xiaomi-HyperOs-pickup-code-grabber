# 4.0 测试与发布计划

> 目标：不是“编译通过”，而是“真机上不会悄悄丢码、不会白屏、不会被任意 App 冒充、升级不丢数据”。

## 一、测试分层

| 层 | 范围 | 工具 | 门槛 |
|---|---|---|---|
| 特征化测试 | 锁定 3.1.0/4.8 现有提取行为 | JVM + 现有语料 | 重构前后 100% 一致 |
| 单元测试 | 提取、规则、去重、模板、脱敏、调用方策略 | JUnit 5 | 全绿 |
| 数据层测试 | Room DAO、事务、去重、outbox | Robolectric / in-memory Room | 全绿 |
| UI 测试 | 双模式、首启、切模式、深色、字体 1.3× | Compose UI Test | 关键路径全绿 |
| 组件测试 | Widget Provider、通知动作、PendingIntent | Robolectric + 真机 | 关键路径全绿 |
| 真机端到端 | 短信→本地→通知→小组件→已取件→系统待办 | K90 Pro Max | 全绿 |
| 安全测试 | 未授权 IPC、SQL 注入、日志脱敏 | adb + 单测 | 0 高危 |
| 性能测试 | Hook、冷启动、小组件、ANR | adb / logcat / Macrobenchmark | 达预算 |
| 兼容测试 | HyperOS / ColorOS / Android 12–17 | 真机/模拟器 | 矩阵内通过 |

## 一·补、4.0 收口实测结果（2026-10-30 晚）

> 这一版把离线回归合成单一入口 `tools/run_all_tests.sh`，
> 并补上了此前漏掉的 RulesRegression / UserRulesTest / FeatureTest / DataTest。

| 套件 | 断言数 | 结果 |
|---|---|---|
| FullReg（全量规则 + 地点） | 20 | PASS |
| ChannelParity（短信/通知双通道一致） | 7 | PASS |
| PkgInputTest（包名输入） | 8 | PASS |
| TierExclusionTest（语义排除红线） | 21 | PASS |
| CallerPolicyTest（IPC 白名单） | 11 | PASS |
| UserRulesTest（用户规则安全） | 45 | PASS |
| FeatureTest（配色对比度 / 提醒调度 / 导入解析） | 42 | PASS |
| DataTest（保留期 / 导出导入 / 规则完整性） | 64 | PASS |
| RulesRegression（内置规则副本） | 39 码 + 13 地点 | PASS |
| RuleLab（`rules/rules.json` 直跑） | 39 | PASS |
| 规则单源一致性（三副本逐字段） | — | OK · v7 / 13 锚点 |
| **合计** | **309 项断言** | **全绿** |

### 真机（REDMI K90 Pro Max / HyperOS 4.0 / Android 17）

| 项 | 结果 |
|---|---|
| 真实短信 → PROVIDER-INSERT → 提取 → Provider → 本地库 → 小组件 | PASS（pending=3） |
| Provider UID 白名单 | PASS（shell UID 调 `onSms` 被拒 `rc=-3 unauthorized`） |
| 启动无崩溃 | PASS（logcat 无 FATAL） |
| 深色模式全页可读 | PASS（8 种强调色的 onAccent 对比度全部 ≥ 4.5） |
| 设置页 14 行 / 四分组 | PASS（逐项核对） |
| 导出 JSON 落盘 | PASS（`/sdcard/Download/取件记录-*.json`，7 条，不含短信正文） |
| 筛选 + 搜索组合 | PASS（「已取」→1；「待取」+「88-8」→1） |
| 小组件隐藏取件码 | PASS（预览与实际一致） |
| 错题本默认关闭 | PASS |
| 规则热更完整性校验 | 逻辑 PASS；**本机网络不可达**（两个镜像都连不上），如实提示「网络不可用」 |

### 发布产物

| 项 | 值 |
|---|---|
| APK | `artifacts/pickup-code-grabber-4.0.0-release.apk`（5,953,824 字节） |
| SHA-256 | `954e5652bc95579555040ed292b968aeac020d7343994592167ed1f6553bc960` |
| versionCode / versionName | 400 / 4.0.0 |
| 证书 | `CN=PickupCodeGrabber, OU=Personal, O=PickupCodeGrabber, C=CN` |
| 证书 SHA-256 | `fba0e7cc3fae670fc846d99c77a36c130045d4eb8ab28a2a52066551e8b78d8b` |

> 签名与上一版 4.0.0 候选包一致，可原地覆盖升级。

---

## 二、现有测试资产

- `RulesRegression`：28 条码 + 13 条地点，当前全过。
- `TierExclusionTest`：21 项语义排除。
- `UserRulesTest`：45 项用户规则安全/匹配。
- `ChannelParity`：短信与通知通道 7 项一致性。
- `FullReg`：20 项全量回归。
- `test/sms-corpus-public.jsonl`：18 条公开脱敏语料。
- `tools/device-probe/`：真机探针。

这些是 4.0 的“行为基线”，不是可随手改的测试。先扩到至少 **100 条**真实/脱敏语料，再开始分层重构。

## 三、4.0 新增测试

### 3.1 特征化测试

- 对当前 `PickupExtractor` 的输入/输出做快照：码、来源、地点、命中层级、规则 ID。
- 覆盖：锚点短信、凭/到/取、横线码、字母码、裸数字、掩码手机号、订单号、柜号、营销短信、多码簇、多段短信、通知文本。
- 重构 `:core` 后逐条对比；任何差异必须解释并更新预期，不允许“大概对”。

### 3.2 本地库与去重

- 同一事件重复上报 10 次：只建 1 条记录、只发 1 次通知。
- 同一码+地点 24h 内重复短信：更新 `last_seen_at`，不新建。
- 同一码不同地点：允许两条。
- 写入系统待办失败：本地记录保留、outbox 重试、界面显示“同步失败”。
- App 被杀/重启/手机重启：记录、状态、小组件保持一致。
- 数据库迁移：空库、旧版本库、损坏库（降级/重建 + 提示）。

### 3.3 小组件

- 收到短信 1 秒内更新。
- 3 条记录各自 PendingIntent 不串（唯一 data URI）。
- 点码复制；点已取件立即消失；点行打开详情。
- 深色/浅色、字体 1.3×、不同桌面网格。
- App 被杀、手机重启、升级后仍能刷新。
- 空状态、超过 3 条显示“+N”。

### 3.4 双模式

- 新安装：首启出现选择页；默认简洁。
- 老用户升级：保留设置；首启出现一次选择页，默认简洁。
- 切换模式：数据、待取件数量、设置不变。
- 简洁模式：打开→复制 ≤ 2 次点击；高级入口可找到。
- 进阶模式：功能不少于 3.1.0 + 4.8 并集。
- 深色/浅色/手势导航/字体 1.3× 无遮挡、无截断。

### 3.5 安全测试

| 用例 | 预期 |
|---|---|
| `adb shell content call --uri content://io.github.okaidev.pickupcode.provider --method onSms ...`（shell UID 2000） | 拒绝，不写库、不弹通知 |
| 第三方 App 调用 `onSms` | 拒绝 |
| 第三方 App 调用 `markDone` | 拒绝 |
| `adb shell am start -n .../.LauncherActivity --es done <code>` | 不执行标记 |
| 向旧 `SmsEventReceiver` 发广播 | 不存在/被拒绝 |
| `code=%`、`code=' OR 1=1--` | 不批量标记、不注入 |
| 诊断报告/日志 | 不含完整手机号、地址、短信正文 |
| 小组件 PendingIntent | 全部 IMMUTABLE，唯一 data URI |
| Provider 高频调用 | 限流，不 ANR、不刷爆通知 |

### 3.6 真机端到端

设备：REDMI K90 Pro Max / HyperOS 4.0 / Android 17（当前未连接，测试阶段再插）。

必测：

1. 真实短信 → 本地记录 → 通知 → 小组件 → 标记已取件 → 小米笔记同步。
2. 一条短信多码；多段短信；网络短信；通知通道（菜鸟/丰巢/京东/拼多多/设备互联）。
3. LSPosed 作用域缺失/齐全；root 未授权/已授权；sqlite3 缺失/已部署。
4. App 被冻结/被杀；手机重启；升级安装；断电重启。
5. 系统待办表不存在、schema 变化、写入失败。
6. 自定义规则：保存、自测、启用、导入导出、危险正则拦截。
7. 诊断报告导出、脱敏、分享。

ColorOS 16：有社区设备则跑写入/标记；没有则标记“社区验证”，不阻塞小米主线。

## 四、性能与稳定性

- Hook 入口 p95 < 20ms；单条提取 p95 < 5ms。
- App 冷启动首屏 < 500ms；首屏不跑 root。
- 小组件更新 < 1s。
- 主线程 0 次 root/sqlite3。
- 连续 100 条短信/通知压力测试：无丢码、无 ANR、内存不持续增长。
- 48 小时 soak：正常收发、开关 App、切模式、重启、冻结，无崩溃/ANR。

## 五、发布门槛（缺一不可）

1. 所有 P0 测试通过；无 P0/P1 未修 bug。
2. 安全清单全过：未授权 IPC、done extra、广播、SQL 注入、日志脱敏。
3. 本地库 + 小组件 + 双模式在 K90 Pro Max 端到端通过。
4. 从 3.1.0 升级安装通过，设置保留，系统待办不被破坏。
5. 48 小时 soak 通过。
6. 版本号、CHANGELOG、README、截图、LSPosed 描述一致。
7. 签名 APK 校验：`versionCode=400`、`versionName=4.0.0`、证书 `CN=PickupCodeGrabber`。
8. 用户明确确认“可以推送”。

## 六、发布流程

1. 从 `main` 拉 `release/4.0`，冻结功能。
2. `version.properties` 改为 4.0.0 / 400；更新 CHANGELOG/README/截图。
3. 跑完整测试；构建 release APK；校验签名/版本/SHA-256。
4. 在 K90 Pro Max 上覆盖安装 3.1.0 → 4.0.0，跑验收清单。
5. 48h soak；记录结果。
6. 打 tag `v4.0.0`；**经用户确认后** push。
7. GitHub Release 上传 APK + SHA-256；LSPosed 镜像同步；酷安发帖。
8. 发布后 7 天观察反馈；准备 4.0.1 修复窗口。

## 七、回滚

- 保留 3.1.0 签名 APK 与 SHA-256。
- 4.0 本地库是新增数据；回滚到 3.1.0 不会删除系统待办，但 3.1.0 看不到本地库记录。
- 回滚前提示用户导出本地记录（JSON/CSV）。
- 数据库迁移只向前；若 4.0.1 需要修 schema，走 Room migration，不降级。

## 八、Definition of Done

- 本地取件库是事实源；系统待办可写穿、可关闭、失败可重试。
- 桌面小组件可用，重启/冻结/升级后仍工作。
- 简洁/进阶双模式首启可选、随时切换、数据共享。
- 未授权 App 无法注入/标记；诊断不泄露正文。
- 全量测试、真机端到端、48h soak 通过。
- 文档、版本、APK、LSPosed/酷安文案一致。
- 用户验收并明确同意推送。
