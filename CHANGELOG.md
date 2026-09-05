# 更新日志（Changelog）

> 版本策略：对外发布延续内部版本号。完整生命周期故事见 [docs/HISTORY.md](docs/HISTORY.md)。

## [2.4.0] - 2026-09-05 · 应用 ID 迁移第二跳（组织前缀，最终包名）

- **应用 ID 变更：`io.github.okai.pickupcode` → `io.github.okaidev.pickupcode`**；
- 原因：官方申诉结论（[appeal #1748](https://github.com/Xposed-Modules-Repo/submission/issues/1748)）——
  GitHub 用户名 `o-kai` 含连字符无法用作 Android 包名，`okai` 用户名又已被他人注册；
  按官方给出的选项**创建组织 `okaidev`（成员公开）**，前缀 `io.github.okaidev.`
  可被官方机器人自动验证通过，无需再依赖人工特批；
- **⚠️ 老用户升级须知：本次仍需卸载重装**（迁移步骤同 v2.3.0，小米笔记数据不受影响）；
- 功能与 v2.3.0 / v2.2.0 完全一致（纯身份变更，此为最终包名）。

## [2.3.0] - 2026-09-05 · 更换应用 ID（为进入 LSPosed 官方模块仓库）

- **应用 ID 变更：`com.pickupcode.grabber` → `io.github.okai.pickupcode`**；
- 原因：LSPosed 官方模块仓库要求包名符合反向域名所有权（自有域名或
  `io.github.<GitHub用户名>` 免验证，见 [submission #1725](https://github.com/Xposed-Modules-Repo/submission/issues/1725)
  官方审核意见）。`O-kai` 含连字符不符合 Java 包名规范，转为 `okai`；
- **⚠️ 老用户升级须知：本次必须卸载重装**（应用 ID 变更无法覆盖安装）：
  1. 备份：小米笔记待办数据在小米笔记应用内，不受卸载影响；
  2. 卸载旧版 → 安装 v2.3.0 → LSPosed 重新启用模块 → 重新勾选 5 项作用域 → **重启手机**；
  3. Magisk 重新授权一次 → 打开 App 跑「部署体检」→「一键部署 sqlite3」（如需要）→「一键测试」；
- 功能与 v2.2.0 完全一致（无新增功能，纯身份变更）。

## [2.2.0] - 2026-09-05 · 一键部署版

- **内置 sqlite3（重磅）**：APK 自带经实机验证的 sqlite3 3.53.4（arm64）+ 依赖库；
  设置页新增「🚀 一键部署 sqlite3」——体检发现缺失且 root 可用时自动出现，
  一键完成释放 → su 拷贝 → 赋权 → 版本验证，**彻底告别 adb/Termux 手工部署**；
- **LSPosed 状态自动检测**：root 读取 LSPosed 配置库（实测 schema），
  体检直接判定：模块是否启用、作用域缺哪一项（逐个点名），并给出修复路径；
  同时检测出 v2.1.1 推荐的无效 `system` 残留项（提示删除）；
- **通知权限体检**：Android 13+ 未授权通知时明确提示；
- 体检升级为 6 项：LSPosed 注入 / root / **LSPosed 作用域** / **通知权限** / sqlite3 / 笔记库；
- 兼容说明：HyperOS 将短信库进程合并进 `com.android.phone`，作用域判定将
  `com.android.providers.telephony` 列为推荐项（其他 ROM 必要，HyperOS 上缺失不影响）。

## [2.1.2] - 2026-09-05 · 部署诊断版（酷安网友反馈修复）

- **修复作用域推荐错误（重要）**：
  - `system` → `android`：LSPosed 中系统框架的包名是 `android`，旧写法匹配不到任何应用；
  - 补上 `com.android.providers.telephony`：S8 主捕获通道所在进程，旧推荐漏勾导致网络短信捕获失效；
  - 现在推荐作用域共 5 项，模块内使用说明与 README 已同步更新；
- **新增「部署体检」**：设置页状态卡改为动态体检（LSPosed 注入 / root / sqlite3 / 笔记库四项，
  逐项 ✅❌ + 修复指引），替代原来静态的"模块已激活"；
- **新增「导出诊断报告」**：一步收集设备信息、体检结果、最近写库失败原因、
  PICKUPDEBUG 日志（root 提取，含被 Hook 进程）、LSPosed modules.log、注入进程清单，
  自动脱敏（手机号 / 取件码）后保存到 Download 并拉起系统分享——发反馈附上此文件即可；
- **模块自注入标记**：模块被 LSPosed 加载时写入标记文件，体检据此判断"作用域是否勾了模块自身 + 是否重启过"；
- **一键测试失败原因显性化**：失败时直接显示断在哪一步（如"全部 su 路径失败 exit=127"），
  不再只显示"无"；
- **注入进程日志**：记录被加载的每个进程 + 未命中目标的包名（最多 20 条），便于报告判定"作用域勾错"。

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
