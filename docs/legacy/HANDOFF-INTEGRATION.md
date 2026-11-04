# 交接包吸收与冲突裁决（2026-10-30）

> 来源：`D:\project\sms-pickup-module\_handoff-2026-10-30\`（12 个文件，由上一任 Agent 生成）
> 处理方式：与当前代码、真机日志、Stage 0/2 实测结果逐条比对，冲突以可复核证据为准。
> 结论：交接包的**设备级 hack 清单**和 **ADR** 有效并被采纳；**测试与版本状态**部分已过时。

---

## 一、冲突裁决

| # | 交接包说法 | 实测/代码事实 | 裁决 |
|---|---|---|---|
| 1 | RulesRegression 因“子进程 javac 死锁”暂挂，需人工跑 | Stage 0 已跑通 **32/32 码 + 13/13 地点**。根因不是死锁，是子进程 javac 遇到**非 ASCII 工作目录**（`D:\project\WorkBuddyData\取件码助手\...`）时输出流阻塞 | **不采纳“永久挂起”**。测试本身可用；需在 ASCII 路径或 CI 下运行，长期方案是改为纯 JVM 断言，不再起子进程 |
| 2 | UserRulesTest 因 `UserRules→Diagnostics` 闭包无法自动化 | Stage 0 已跑通 **45/45**（用 `android.jar` + 显式源文件列表编译） | **不采纳**。真正的问题是编译依赖没有收敛，不是测试不可运行；Stage 1 拆 `:core` 后自然解决 |
| 3 | build.gradle 400/4.0.0 vs Manifest 310/3.1.0，版本漂移 | Stage 0 已引入 `version.properties` 单一版本源，Manifest 不再写版本 | **已解决**，交接包记录的是修复前状态 |
| 4 | docs/ARCHITECTURE.md 与实现差异大 | 确认存在；且交接包自身也是 3.1 架构（仍写 `SmsEventReceiver exported=true`）与 v4.8 混写 | **两份都不能直接用**。以 `docs/ARCHITECTURE-4.0.md` + 本文第二节为准 |
| 5 | v4.8 工作区有 3 个未提交文件 | 复查原仓库 `git status` 干净，`fa244f3` 已含设备互联/图标改动 | **无遗漏**，工作副本与之一致 |

---

## 二、必须保留的设备级 Hack（重构红线）

这些是多次真机排查换来的，改动前必须有等价替代 + 真机验证，不能“顺手清理”。

| Hack | 位置 | 为什么不能动 |
|---|---|---|
| sqlite3 `p_align=0x4000`（16KB 对齐） | `Repair.deploySqlite3()` / `app/assets/sqlite3/sqlite3` | HyperOS 4.0 / Android 17 上非 16KB 对齐的 ELF 无法执行 |
| sqlite3 只能部署到 `/data/data/<pkg>/files/sqlite3` | `Repair.java` | 该路径是 SELinux 允许 exec 的位置，换路径直接 `Permission denied` |
| `NotiHook.buildText()` 补全 7 个字段 | `NotiHook.java` | `EXTRA_TEXT_LINES / SUB_TEXT / SUMMARY_TEXT / INFO_TEXT / BIG_TEXT / MESSAGING_STYLE` 缺一个就抓不到对应 App 的码 |
| `hasFeatureWords` 前置门禁 | `NotiHook.handleNotification()` | 白名单命中后必须无条件尝试提取，否则“有码无特征词”的通知会被漏掉 |
| `BUILTIN_PKGS` 顺序（设备互联排最后） | `NotiHook.java` | 顺序即 UI 显示顺序，已被用户确认 |
| `AppIcons.bindSystemIcon()` 回退 | `AppIcons.java` | 任意包名取系统真实图标，无图标时兜底菜鸟；手动添加来源依赖它 |
| `ProcessSync` 文件管道 | `ProcessSync.java` | 系统进程无 Context，配置只能走共享文件，不能换成普通 IPC |

**补充约束**：系统进程（`system_server`）绝对不能引入 AndroidX / Room / Compose。Hook 侧保持纯 Java + `compileOnly` Xposed API。

---

## 三、从 ADR 吸收的产品决策

| 决策 | 影响 |
|---|---|
| 三条铁律：可用性 / 安全性 / 稳定性，宁可功能少 | 4.0 的功能扩张必须让位于稳定性 |
| 发布门槛：有实际产出才推送；push / Release / LSPosed / 酷安都要用户确认 | 当前所有改动仅本地，未推送 |
| 来源/地点缺失显示「—」，不回显号码 | 已在首页与列表实现（`place == "—"` → 简洁模式显示“来源未识别”） |
| 通知白名单用 `LinkedHashSet`（去重 + 保序） | 自定义来源增删依赖此结构 |
| 三路写库后端（小米笔记 / ColorOS 日历 / ColorOS 便签），自动识别 | 4.0 改为「本地库为事实源，系统待办为可选写穿」 |
| 规则三副本（远端 / assets / 代码内置） | **4.0 修订**：仓库内单一源 `rules/rules.json`，构建时生成 Java 常量 + assets，见下 |
| 纯添加弹窗、红叉移除、Tab 改名「取件通知 / 高级规则」 | 已存在于 v4.8 UI，4.0 保留 |

### 对规则单源方案的修订

原 ADR 认为“三副本不可避免”，理由是系统进程读不到 assets。这个约束成立，但**不等于要手工维护三份**：

```
rules/rules.json（唯一人工编辑）
      │  Gradle 任务 generateRules
      ├─→ app/assets/rules/rules.json（运行期兜底）
      └─→ ExtractorRulesDefaults.java（编译进系统进程的常量）
```

校验脚本 `test/check_rules_consistency.py` 继续保留，作为生成结果的断言。

---

## 四、需要标注的已知限制（进 4.0 发布说明）

| 项 | 状态 | 写法 |
|---|---|---|
| 跨设备互联通知 | 仅单机验证图标与白名单命中，无第二台设备 | “设备互联需两台小米设备均开启；跨设备通知未做端到端验证” |
| ColorOS 16 | 无设备 | “ColorOS 后端代码保留，未在 ColorOS 16 实机验证” |
| 小组件直接添加 | HyperOS 4.0 上 `requestPinAppWidget` 被桌面静默取消 | 应用内提供手动添加步骤；不承诺一键添加 |
| PR #8 / #9 | 本地已合并，GitHub 仍 open | 待用户决定是否标记 merged（涉及公开历史，不擅自操作） |

---

## 五、据此调整的后续工作

1. **Stage 1**：`:core` 纯 JVM + 规则单源生成；把 RulesRegression / UserRulesTest 改成不依赖子进程 javac 的断言。
2. **Stage 4**：诊断报告 JSON 化；sqlite3 部署后自检 `--version` 并支持失败回滚（交接包第 7 问）。
3. **发布前**：补发布检查清单模板（交接包第 10 问），把“用户确认节点”写死。
