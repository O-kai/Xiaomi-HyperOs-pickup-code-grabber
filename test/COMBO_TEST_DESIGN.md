# 取件码助手 — 组合场景回归测试设计

> **审计快照**：2026-10-04 03:27 (Asia/Shanghai)，工作区 = v3.1.0 未提交态（git HEAD `4ae4d70`，15 个文件 modified）。
> **重要**：审计期间工作区被并发修改了至少 5 次（TodoWriter / PickupExtractor / SystemDirectWriter / SmsEventReceiver / ProcessSync / LauncherActivity / ExtractorRules）。本报告已按 03:27 的最终状态复核。
> 所有结论均标注行号。**"确认"** = 仅凭代码即可证明；**"待验证"** = 代码路径存在，但运行时前提需真机确认。
> 严重度：🔴 用户实际损失 · 🟡 功能不稳定/不一致 · ⚪ 仅整洁

---

## 0. 审计期间已被并发修掉的问题（**必须回归验证，新代码无测试覆盖**）

这几条是本次审计提出后被立即修复的，**属于未测过的新代码**，回归时要重点盯：

| # | 问题 | 修复位置 | 修法 | 严重度（修复前） |
|---|---|---|---|---|
| F1 | `seen.add(fingerprint)` 在 `writeTodo` **之前**执行且失败不回滚 → 一次 su 超时就把该码永久标记"已写" | TodoWriter.java:96-111 | 写失败时 `seen.remove(fingerprint)` | 🔴 |
| F2 | `handle()` 无同步 + SharedPreferences 读-改-写 → 两条通道同时到达丢指纹 → 同码重复写待办 | TodoWriter.java:87-126 | 新增 `DEDUP_LOCK` 包住整段 | 🟡 |
| F3 | Provider 用 `hasFeatureWords`、Receiver 用 `lookLikePickupSms` → 同一条短信两条通道结论不同（错题本收不收得到取决于走哪条路） | SmsEventReceiver.java:48 | 统一为 `hasFeatureWords` | 🟡 |
| F4 | 兜底通道黑名单分隔符少 `、;；` → 用户用顿号分隔的黑名单词在兜底通道整词失效 | SystemDirectWriter.java:126 | 改为 `[,，、;；\s]+` | 🟡 |
| F5 | 用户手动指定"写入目标"不同步给系统进程 → 主/兜底写进两个不同的库 | ProcessSync.State.backend / SystemDirectWriter.java:151-154 | 加了 `backend` 字段 | 🔴 |

**F1–F5 都没有对应的离线回归用例。** 建议在 `test/` 下新增 `ConcurrencyTest.java` 与 `CrossChannelTest.java`。

---

## 1. 跨进程组合场景

| # | 场景 | 当前代码会发生什么（依据） | 期望 | 一致? | 严重度 |
|---|---|---|---|---|---|
| C1 | **同一条短信被 mms 进程与 telephony 进程同时捕获** | 两个进程各有独立 `SmsBridge.RECENT`(SmsBridge:27)，互不可见 → 都调 `getContentResolver().call` 进模块进程 → 落到 `TodoWriter.handle` 的 `DEDUP_LOCK` + `seen`(code\|place) 上被拦下 | 只写 1 条 | ✅ 一致 | — |
| C2 | **同一取件码同时出现在短信与快递 App 通知** | 短信通道 place 来自短信正文，通知通道 place 来自 `title+text+bigText` 拼接(NotiHook:155-168)。**去重指纹是 `code+"|"+place`(TodoWriter:89)，不含 sender/body** → 两边 place 不同 ⇒ **同码写两条待办** | 只写 1 条 | ❌ **不一致** | 🔴 |
| C3 | **模块 App 通道 + 冻结兜底通道同时写** | 两套去重口径不同：App 用 SharedPreferences 里的 `code\|place`；兜底用**查目标库** `content LIKE '%code%'`(SystemDirectWriter:166-168, NotesBackend:213) | 口径统一 | ❌ 不一致 | 🟡 |
| C4 | **兜底通道 + 用户手动改"写入目标"** | `fallback()` 已按快照 backend 算 `db`(SDW:151-154) 用于 `exists` 探针，但 `runSqlFile()` **重新用 `propsBackend()` 算库路径**(SDW:199-201)。SQL 文本又按快照 backend 的表结构生成(SDW:170) → **探针查 A 库、INSERT 打进 B 库**；若两库表结构不同 → `no such table` → rc=-1，静默丢失 | 写进用户指定的库 | ❌ **确认的新回归** | 🔴 |
| C5 | **通知通道 + 短信通道规则版本不一致** | `NotiHook.install` 在 system_server 启动时读一次 `ProcessSync.load()`(NotiHook:59)；`SystemDirectWriter.loadStateOnce()` 每进程一次(SDW:67-76) → **改黑名单/模板/规则/写入目标后，必须重启手机才对系统通道生效**。UI 只在通知包名处提示"需重启手机"(LauncherActivity:1742)，**黑名单/模板/规则处无提示** | 即时或明确提示 | ❌ | 🟡 |
| C6 | **用户停用自定义规则（App 侧已关）** | `setEnabled(false)` 只写同步文件(UserRules:213-216)；系统进程 `if (userRuleCount()==0) reload`(SDW:108-110) → 缓存非空时**永不重载** → 用户规则在系统通道继续生效直到重启 | 停用立即生效 | ❌ | 🟡 |
| C7 | **同一短信走 Provider 通道 vs 广播兜底通道** | 两条通道都汇到 `TodoWriter.handle`（F3 修后门禁已统一），但 **Provider 在 Binder 线程、Receiver 在主线程** → 同一逻辑的超时/ANR 行为完全不同（见 R1） | 一致 | ❌ | 🟡 |
| C8 | **第三方 App 伪造事件** | `TodoProvider` `exported="true"` 且**无 `android:permission`**(AndroidManifest:40-43)。任意 App 可 `call(method="markDone", arg=...)`(TodoProvider:37-44) 让模块用 **su** 执行 SQL；`LIKE '%code%'` **未转义 `%`/`_`**(NotesBackend:190,196,199,213) → `arg="取"` 会把**所有含"取"字的待办标记为已取件** | 仅本模块可调用 | ❌ **确认** | 🔴 |

---

## 2. 状态组合场景

| # | 交叉维度 | 当前行为 | 严重度 |
|---|---|---|---|
| S1 | **黑名单「清空」× 冻结兜底开关开** | App 侧清空 = 全放行(LauncherActivity:691-695 → TodoWriter:155 `if(list.isEmpty()) return false`)；但 `ProcessSync` 推的是空串(ProcessSync `buildJson`)，兜底侧 `if(!st.blacklist.trim().isEmpty())` 不成立 → **回落 `DEFAULT_BLACKLIST` 8 词**(SDW:119-125)。→ 用户明令"不过滤"，兜底通道仍在过滤 | 🔴 |
| S2 | 黑名单用 `、` `；` 分隔 × 兜底通道 | 已修（F4）。**需回归** | 🟡→✅ |
| S3 | 模板模式 极简/完整/自定义 × 兜底通道 | 已通过 ProcessSync 同步(SDW:128-131) | 🟡 需回归 |
| S4 | 模板为自定义但 `{place}` 取不到 | `place="—"`(PickupExtractor:365) → 正文出现 `｜—｜` 空洞 | ⚪ |
| S5 | 通知开关开 × 来源包名不在白名单 | `handleEnqueue` 先 `isWhitelisted`(NotiHook:102) 再 `isFlagOn`(103) → 不在白名单直接返回，**开关无意义** | ⚪ |
| S6 | 通知开关改 × `PKG_WHITELIST` 热改 | 开关文件**每条通知都读**(NotiHook:133-144，即时生效)；白名单只在 install 时读一次(C5) | 🟡 体验不一致 |
| S7 | 小米 ROM × 冻结兜底开关 | `updateSdwDisplay` 在非 ColorOS 下隐藏 UI 并把 pref 置 false(LauncherActivity:1053-1058 区)，但**只写 SharedPreferences，不写 `/data/local/tmp/pickup_sqlite/enable_sys_direct_write` 标志文件** → 若此前开过，**兜底通道仍是开的** | 🟡 |
| S8 | 自定义规则启用 × 官方规则热更 | 用户规则在 `extract()` 入口最优先，命中即 return(PickupExtractor:413-420) → 热更的官方规则对该短信无效（设计如此），但 UI 显示的"规则集 v N"会误导 | ⚪ |

---

## 3. 数据形态边界

| # | 输入形态 | 当前行为 | 严重度 |
|---|---|---|---|
| D1 | **一条短信多码簇** | 提取引擎返回多个码(TierA `addCluster`) → 循环逐个写(SDW:163-172 / TodoWriter:88-112)，每码一条待办，**共用同一 place/source/time** | ✅ 符合预期 |
| D2 | **完全无码** | `codes.isEmpty()` → 有特征词则记错题本(TodoWriter:64-72)，否则静默 | ⚪ |
| D3 | **有码但地点提不到** | `place="—"` → 正文出现 `｜—｜`；`buildTitle` 在首个 `｜` 前截断(TodoWriter:232-237) | ⚪ |
| D4 | **超长码（>20 字符）** | `validCode` 硬拒 `tok.length() > 20`(**PickupExtractor:583**)，但 `dashPattern` 允许 4 段、理论上限 ~28 字符(ExtractorRules:26) → **正则匹配上了却被 validCode 丢掉** | 🟡 |
| D5 | **多段横线码 `5-5-9-13`** | 冒烟用例已覆盖 ✅ | — |
| D6 | **纯字母/纯数字 3-9 位** | `alnumPattern="[A-Za-z]?\\d{3,9}"`(ExtractorRules:27)；**2 位及以下必丢** | 🟡 |
| D7 | **含 `'` `\` 换行 emoji `"`** | `sqlEscape` 只做 `'`→`''` + 去 `\0`(TodoWriter:321-324)。SQLite 字符串字面量中反斜杠/换行/双引号均无需转义 → **注入面安全** ✅（测试不要在这里浪费时间） | ✅ |
| D8 | **`%` `_` 出现在取件码** | `validCode` 形状限定 `[A-Za-z0-9-]` → 不会出现；但 **`markDone`/`exists` 的 `LIKE` 未转义 `%`/`_`**，且用**子串**匹配 → **`2-2-75` 会命中 `12-2-7508`**(NotesBackend:199,213) | 🟡 |
| D9 | **超长正文** | 无长度上限；TierD 窗口固定 24 字(PickupExtractor:466) | ⚪ |
| D10 | **TierA/B 新增语义排除的副作用** | 工作区新加 `addIfValidSemantic`，TierA/TierB 现在也跑 `isExcludedToken`；`excludeTailWords` 含 `号/个/件/单/天/元` 等单字 → **紧跟「号/个」的合法码会在最高置信层被丢**。现有 21 条冒烟用例码后全都有分隔符，**覆盖不到** | 🟡 **新回归风险** |
| D11 | **跨天/跨月/跨年** | `{time}` 用设备本地时区 + **处理时刻**（`new Date()`，TodoWriter:74），`SmsBridge` 传来的 `ts` **全程未使用**（仅日志，TodoProvider:48-49）；`NotesBackend` 的 `create_time` 用 UTC epoch | ⚪ |
| D12 | **同一码在 500 条新码后再次到达** | `seen` 上限 500，超出按插入序淘汰最旧(TodoWriter:117-121) → 老码可被重复写入 | ⚪ 设计如此 |
| D13 | **ColorOS 便签后端 + 来源含 `<` `>`** | `rich_notes.raw_text` 把 content 直接拼进 HTML(NoteBackend:144-145)，**未做 HTML 转义** → 破坏便签渲染。可经用户规则 `source`/`place` 或通知标题触发 | 🟡 |

---

## 4. 失败路径组合

| # | 故障 | 当前代码会发生什么 | 用户能否感知 | 严重度 |
|---|---|---|---|---|
| R1 | **su 授权弹窗未响应（超时）** | `TodoWriter.runSqlForOutput` 用 `p.waitFor()`，**无超时**(TodoWriter:188)；`SystemDirectWriter` 同样(SDW:209,238,258)。对比 `Diagnostics.suExec` **有** `waitFor(timeoutSec)`(Diagnostics:374) → 写库路径是唯一无超时的。**且 `writeTodo` 在 `DEDUP_LOCK` 内**(TodoWriter:87,98) → 一次挂起 = **锁永久不释放，后续所有短信全部阻塞**。Receiver 通道在主线程 → **模块 App ANR**；Provider 通道耗尽 Binder 线程池 → `SmsBridge` 的 `getContentResolver().call` 阻塞 → providers.telephony 的短信入库 binder 线程阻塞 | 只有诊断报告能看到 | 🔴 |
| R2 | **sqlite3 文件丢失/损坏** | 5 个 su 路径全失败 → `writeTodo=false` → 指纹回滚(F1 已修) → 至少不会"假装写过"。但 `TodoProvider` 只在 `!wrote.isEmpty()` 时通知(TodoProvider:63-67) → **真实短信到达时完全静默** | 仅一键测试/诊断报告 | 🔴 |
| R3 | **目标 App 未装 / 未建过待办** | `checkNotesDb` 已提示"请先建过"(Diagnostics:207-231)。但注意：`sqlite3 <db> < script` **会创建空的 todo.db**（NotesBackend:119 路径）→ 后续小米笔记自建库可能与 root 建的空库冲突 | 无 | 🟡 |
| R4 | **`/data/local/tmp` 同步文件损坏/为空** | `ProcessSync.load()` 任一步失败都**静默返回默认 State**(ProcessSync `load()` 多个 `return st`)。默认 = `rulesJson=""` → 系统通道**回落到编译内置 v5**，用户热更到 v6 的规则在通知/兜底通道完全不生效，且无任何提示 | 无 | 🟡 |
| R5 | **网络断开检查更新** | 已修：只有真拿到结果才记时间戳(`stampOnSuccess`)。断网不锁死 3 天 ✅ | Toast 提示 | ⚪ |
| R6 | **App 被强制停止/冻结** | Provider `call` 抛异常 → `SmsBridge.flush` catch → 广播兜底 → 再失败 → `SystemDirectWriter.fallback`(SmsBridge:279-301)。但 `obtainContext()` 返回 null 时 `ctx2==null` → **兜底被跳过**(SmsBridge:298) | 无 | 🟡 |
| R7 | **`ProcessSync.push` 并发** | 每次调用都 `new Thread`(ProcessSync:101)，**无串行化** → 两条线程同时 `cat > app_state.json` → 截断/半截文件。且 `pushThrottled` **先记时间戳再推**(ProcessSync:86-88) → su 失败后 30 分钟不重试 | 无 | 🟡 |
| R8 | **隐私泄漏** | `XposedEntry.log("SMS: ... body=" + 300 字符)`(SmsBridge:258-260)、`blacklist skip: 60 字符`(TodoWriter:60)、Receiver 记 120 字符(SmsEventReceiver:35) → 全进 `PICKUPDEBUG` → `collectReport` 整段贴进报告(Diagnostics:286-288)。而 `sanitize` 的 `P_CODE` **只匹配 `\d{1,4}-\d{1,2}-\d{1,5}`**(Diagnostics:44) → **`267961` / `A88123` 这类码、以及姓名住址全部明文** | 用户主动导出才发生 | 🟡 |

---

## 5. 竞态

| # | 竞态点 | 依据 | 严重度 |
|---|---|---|---|
| T1 | `DEDUP_LOCK` 覆盖 `writeTodo` | TodoWriter:87-126，锁内含无超时 su 调用 | 🔴（F2 修复引入的新风险） |
| T2 | `runSql`/`runSqlForOutput` **未加锁**且共用固定文件名 `todo_sql.sql` | TodoWriter:164-173；`writeTodo` 是 synchronized(298)，但 `TodoProvider.call("markDone")`(TodoProvider:39) 与 `LauncherActivity.handleDone`(LauncherActivity:1564) 走的是**未加锁的** `runSql` → 两条语句互相截断/串写 → **取件待办被静默丢弃** | 🔴 |
| T3 | `SmsBridge.PENDING` 未加锁 + `cleanupPending` 迭代中 `flush` 里 `remove` | SmsBridge:31、305-311、238 → **`ConcurrentModificationException`**（被 XposedEntry:102 的 catch 吞掉 → 该条短信静默丢失） | 🟡 |
| T4 | 同源短信 8 秒窗口内正文被拼接 | SmsBridge:219-229 `part.body += body`，key 只有 sender → **同号两条独立取件短信在 8 秒内到达会被合并** | 🟡 |
| T5 | 单段广播短信的 flush 条件不成立 | SmsBridge:232：`flushNow=false, inBroadcast=1, parts=1` 时**四个条件全不满足** → 只能靠**同进程内第二个 Hook 点**再推一次凑够 `parts>=2` 才 flush。若某 ROM 只命中一个点 + 用户未勾 `com.android.providers.telephony`（`Repair.SCOPE_OPTIONAL` 标注为"可选"）→ **彻底漏抓** | 🟡（运行时前提待真机确认） |
| T6 | `SystemDirectWriter` 节流 check-then-act 非原子 + `runSqlFile` 未加锁 | SDW:141-146 与 175-176 之间无锁；`runSqlFile` 无 synchronized，两条 binder 线程可同时通过节流并并发写同一个 `sys_direct.sql` | 🟡 |
| T7 | `lastDedupHits` / `lastWriteDiag` 是进程级单值 | TodoWriter:45、35；`ChainTestActivity` 在 `doWrite` 读它(ChainTestActivity:365,374)。**真实短信并发到达会覆盖它** → 一键测试页把"写入失败"显示成"命中去重" | 🟡 |
| T8 | `UserRules.lastHitRule` 进程级单值 | UserRules:596；只在 UI 侧 `clearLastHit()`，引擎侧不清 → 并发下溯源串味（`TraceTest` 只测了串行） | ⚪ |
| T9 | `NotiHook.PKG_WHITELIST` 读不加锁 | install 在 `synchronized (PKG_WHITELIST)` 内 clear/add(NotiHook:62-69)，`isWhitelisted` 无锁(NotiHook:128-130)，且该检查在**每一条系统通知**上先跑 | ⚪ |
| T10 | `TodoWriter` 回写 `todo_mode` 用陈旧值 | TodoWriter:77 读、:124 写。用户在设置页改模式的同时来短信 → **设置被回滚**，UI 下次读取显示旧值 | 🟡 |

---

## 6. 建议的验证手段

### 6.1 脱机 JVM 可测（扩展现有 `RulesRegression` / `SmsLab` 的模式）
在 `test/` 下新建，编译依赖链沿用 `RulesRegression.NEED` 列表。

| 编号 | 用例 | 可执行思路 |
|---|---|---|
| O-1 | **去重并发** | 用桩 `SharedPreferences`（HashMap 实现的假实现）+ 8 线程 × 50 次调 `TodoWriter.handle`，断言最终 `seen` 条数 = 预期总数（验证 F2 的锁真的覆盖了读-改-写） |
| O-2 | **锁持有范围** | 桩 `Runtime.exec` 返回一个永不退出的进程，断言 `handle` 在 N 秒后仍阻塞 → **确认 T1 存在**，并给出挂起时长 |
| O-3 | **长码 D4** | 对 `validCode` 直接喂 21/25/28 字符的 dash 串，断言返回 false；再对 `patternKeyword` 断言它能匹配 → 证明两者上限不一致 |
| O-4 | **TierA/B 新语义排除 D10** | 把 `SmsLab.CASES` 扩一批「码后紧跟 号/个/件/单/天/元」的样本（如 `【菜鸟】取件码284号格口`、`取件码为1234个`），跑 `extract` 断言是否被丢 |
| O-5 | **同源 8 秒拼接 T4** | 反射拿 `SmsBridge.PENDING`，连续 `pushParts("1069","A取件码1",1,false)` + `pushParts("1069","B取件码2",1,false)`，断言拼接结果 |
| O-6 | **LIKE 子串误命中 D8** | 对 `NotesBackend.existsSqlOf("xiaomi","2-2-75")` 断言生成串，再用真 sqlite3 灌两条 `todo`（`2-2-75` 与 `12-2-7508`），跑该 SQL 断言 COUNT 为 2 |
| O-7 | **SQL 注入面 C8** | 对 `markDoneSqlOf("xiaomi","' OR 1=1 --")`、`markDoneSqlOf("xiaomi","%")`、`markDoneSqlOf("xiaomi","取")` 断言转义后 SQL 仍只影响预期行（用真 sqlite3 验证） |
| O-8 | **脱敏漏网 R8** | `Diagnostics.sanitize("取件码267961 和 A88123，姓名张三，地址 XX 路 12 号")`，断言 `267961`/`A88123`/`张三` 是否被替换 → 预期**未脱敏**（坐实 R8） |
| O-9 | **模板/模式 × 后端组合** | 对 `TodoWriter.buildContent` × `{xiaomi,coloros_todo,coloros_note}` × `{0,1,2}` 做 3×3 快照，断言 `place="—"` 与 `place` 有值两种情况下的文案 |
| O-10 | **规则一致性** | `test/check_rules_consistency.py` 已存在；补一条：`ExtractorRules.createDefault()` 的 JSON 与 `app/assets/rules/rules.json` 跑 `runSmokeTest` 结果必须一致 |

### 6.2 必须真机验证

| 编号 | 验证目标 | 手段 |
|---|---|---|
| D-1 | 模块是否注入到全部 5 个进程 | `adb shell "su -c 'logcat -d -s PICKUPDEBUG:* \| grep handleLoadPackage'"`，应看到 `com.android.mms` / `com.android.phone` / `com.android.providers.telephony` / `android` |
| D-2 | system_server 包名到底是 `android` 还是 `system` | `adb shell "su -c 'ps -A \| grep system_server'"` + LSPosed 日志里 `handleLoadPackage: pkg=?`。若为 `system` → **通知通道完全没装**（XposedEntry:46-49） |
| D-3 | 快照文件是否被正确写入/读取 | `adb shell "su -c 'ls -l /data/local/tmp/pickup_sqlite/'"`、`su -c 'cat app_state.json \| base64 -d \| head -c 300'` |
| D-4 | T5 是否真实发生 | 关掉 `com.android.providers.telephony` 作用域，只留 mms，发**单段**取件短信，看 `logcat` 有无 `SMS: sender=` 日志（无 `SMS:` = 没 flush） |
| D-5 | T6 10 秒节流 | 10 秒内发两条不同短信，观察是否两条都写 |
| D-6 | 通知通道 | 用 `adb shell cmd notification post` 不行（需白名单包名）；改用真实 App 推送，或把 `com.android.shell` 加进 `noti_extra_pkgs` 后重启验证 |

---

## 7. ★ 本次回归**必须真机执行**的用例（按优先级）

> 每条都给出：怎么准备 / 点哪里 / 看什么 / 怎么判通过。

### P0-1　单段短信能否被 flush（验证 T5，最可能的"整条功能不工作"）
1. LSPosed 作用域：**只勾 `com.android.mms`**，取消 `com.android.providers.telephony`。
2. 重启手机，打开模块 App 点「排查问题」确认状态。
3. 用真实号码发一条**单段**取件短信（不要分段）。
4. 判定：
   - ❌ 不通过：`logcat -d -s PICKUPDEBUG:*` 里**没有** `SMS: sender=` 这一行。
   - ✅ 通过：有 `SMS: sender=`，且待办里出现该条。

### P0-2　去重指纹回滚（验证 F1 修复）
1. 先在 Magisk 里**撤销**本模块的 root 授权（或临时把 Magisk 设成需要每次确认）。
2. 发一条取件短信（码记为 `X`）→ 预期：无待办、无通知。
3. `logcat` 应出现 `TODO FAIL: 📦 取件码 X…`。
4. 恢复 root 授权。
5. **再发一次内容完全相同的短信**。
6. 判定：✅ 写入成功（说明指纹已回滚）；❌ 什么都没发生（指纹被污染，F1 修复无效）。

### P0-3　su 超时是否卡死全链路（验证 R1/T1）
1. Magisk 设置里把本模块改成"每次询问"。
2. 发一条取件短信，**不响应弹窗**，静置 2 分钟。
3. 再发第二条不同码的短信。
4. 判定：
   - ❌ 严重不通过：第二条也完全没反应，且 `adb shell "su -c 'ls -l /data/local/tmp/pickup_sqlite/'"` 卡住 → 锁死。
   - ✅ 通过：第二条正常写入（说明 `waitFor` 有兜底或锁未跨 su）。

### P0-4　通知通道与短信通道的重复（验证 C2）
1. 设置页打开「通知取件提取」（会写 `/data/local/tmp/pickup_sqlite/enable_noti_hook`）。
2. 重启手机。
3. 打开菜鸟 App 触发一条含取件码的**通知**。
4. 同时让同一取件码的**短信**也到达（或先用通知后用短信，二者文案不同即可）。
5. 判定：
   - ❌ 不通过：待办里出现**两条**内容相近、取件码相同的待办（`place` 不同）。
   - ✅ 通过：只有一条。

### P0-5　冻结兜底与主通道的写入目标一致性（验证 C4）
1. 设置页「写入目标」手动选一个**与 ROM 自动判定不同**的项（ColorOS 上选「ColorOS 便签」；小米上选 ColorOS 目标）。
2. 开启「冻结免疫直写」→ 重启。
3. 把模块 App 从最近任务划掉并冻结。
4. 发一条取件短信。
5. 判定：查 `logcat` 里的 `SYS-WRITE backend=... rc=...`
   - ❌ 不通过：`rc=-1` 或 `rc` 非 0，且你在**指定的** App 里找不到条目 → C4 探针/写入库分裂成立。

### P0-6　黑名单"清空"在兜底通道是否真放行（验证 S1）
1. 设置页把黑名单**清空保存**（提示"所有短信不再被关键词过滤"）。
2. `adb shell "su -c 'cat /data/local/tmp/pickup_sqlite/app_state.json | base64 -d'"`，确认 `blacklist` 为空。
3. 冻结模块 App，发一条正文含**"验证码"**的取件短信（构造：`【菜鸟驿站】取件码9-9-9999 验证码123456`）。
4. 判定：
   - ❌ 不通过：兜底通道没写（`logcat` 有 `SYS-WRITE` 但无 `codes=`）→ 空黑名单回落默认词的问题成立。

### P1-7　跨进程配置是否需要重启（验证 C5）
1. 改黑名单（加一个新词）→ 保存。
2. **不要重启**，直接 `logcat -c` 清日志。
3. 冻结模块 App，发一条含新黑名单词的取件短信。
4. 判定：兜底通道是否用了新黑名单？
   - ❌ 不通过：仍然写入了 → 确认"改设置需重启"且 UI 无提示。

### P1-8　诊断报告脱敏（验证 R8）
1. 收一条 `【妈妈驿站】凭取件码267961至化工镇云水村委文化广场东侧小平房取件` 的短信。
2. 主界面 → 导出诊断报告。
3. 在 txt 里搜 `267961`、`化工镇`、`云水村`。
4. 判定：❌ 不通过——三者都明文出现（`P_CODE` 只脱敏 `\d{1,4}-\d{1,2}-\d{1,5}` 形）。

### P1-9　长码被丢（验证 D4）
1. 一键链路测试里粘贴：`【某驿站】取件码为1-2-3-4-5-6-7-8-9-0-1，请到 XX 快递柜取件`
2. 判定：❌ 不通过——"未识别到取件码"（`validCode` 拒 >20 字符），而正则其实匹配上了。

### P1-10　一键测试页面被并发短信串味（验证 T7）
1. 打开「一键链路测试」，停在第 3 步「写入待办」的结果页。
2. 同时让另一条真短信到达。
3. 判定：❌ 不通过——结果页把成功/失败判断显示反（读到了别的短信的 `lastDedupHits`）。

### P2-11　"已取件"误伤（验证 D8）
1. 写入码 `2-2-75` 和 `12-2-7508` 两条待办。
2. 对 `2-2-75` 点通知里的「已取件」。
3. 判定：❌ 不通过——`12-2-7508` 也被标记为已取件（`LIKE '%2-2-75%'` 子串命中）。

### P2-12　第三方 App 越权（验证 C8，安全项）
1. 在设备上装任意一个能发广播/调用 Provider 的测试 App（或用 `adb shell am`）。
2. 判定：❌ 不通过——`content://io.github.okaidev.pickupcode.provider` 可被外部以 `method=markDone&arg=取` 调用并把待办批量标记完成。

---

## 8. 给回归测试作者的落点建议

1. **新增 `test/ComboRegression.java`**，沿用 `RulesRegression` 的"拷贝源文件 → javac → URLClassLoader 反射调用"模式，覆盖 O-1/O-3/O-4/O-6/O-7/O-8/O-9。
2. **新增 `test/ConcurrencyTest.java`**，用假 `SharedPreferences` + 多线程，专测 F1/F2 与 T1/T2/T7。
3. **扩 `SmsLab.CASES`**：补 D10 的「码后紧跟量词」样本与 D4 的长码样本——现有 21 条冒烟用例**结构上覆盖不到**这两类。
4. **离线测不到的**（必须真机）：T3/T4/T5/T6、C2/C3/C4/C5/C6/C7/C8、R1/R3/R6/R7。理由：依赖 Android Binder 线程模型、ContentProvider 跨进程唤醒、su 弹窗、目标 App 的 SQLite 库状态。

---

**最后提醒**：本报告基于 03:27 的工作区快照。工作区仍在被修改（C1–C5 就是在审计过程中被修掉的）。**执行真机回归前请重新核对行号与哈希**，或先 `git stash` 冻结一个版本再测。
