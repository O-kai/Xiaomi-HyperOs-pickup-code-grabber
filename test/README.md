# 🧪 测试与回归

本目录是项目的**脱机回归测试**（不需要真机、不需要 LSPosed 环境）。
所有测试都用 `android.jar` 作 classpath 动态编译项目源码，可在普通 JVM 上运行。

## 目录内容

| 文件 | 说明 |
|---|---|
| `RulesRegression.java` | **官方规则引擎回归**：跑 `sms-cases.txt` 全部用例 + 冒烟自检 |
| `FullReg.java` | **全量回归（码 + 地点，20 项）**：一次性验证提取码与地点解析，含反例拦截。改规则后首选跑这个 |
| `ChannelParity.java` | **双通道一致性（7 项）**：同一段文本分别用「短信 sender」与「通知 sender」跑，结果必须完全一致。保证短信通道与通知通道共用同一套规则集、不会各跑各的 |
| `ProbeRule.java` | **单条短信分层诊断**：打印 Tier0/TierA/B/C/D 每一层是否命中、命中了什么，用来定位「这条短信为什么没识别」 |
| `ProbeTierD.java` | **TierD 评分复刻**：逐项打印特征词/动作词/码形各得几分、离阈值差多少 |
| `ProbeLook.java` | **门禁诊断**：`lookLikePickupSms` / `hasFeatureWords` / `patternAnyToken` 为何通过或失败 |
| `TierExclusionTest.java` | **语义排除回归（v3.1.0 新增，21 项）**：锁住「TierA/TierB 不得绕过 `isExcludedToken`」。一半用例验证该拦的拦住（格口号/金额/尾号/订单号/掩码手机号），一半验证真码不被误伤——**这组是红线，加新规则时最容易踩坏的地方** |
| `CallerPolicyTest.java` | **IPC 调用方白名单（4.0 Stage 0 新增，11 项）**：验证系统/电话/短信/telephony 包名放行，第三方/伪造包名/null 拒绝 |
| `SecurityChecklist.md` | **真机 IPC 安全验收清单**：未授权 `onSms`/`markDone`、旧广播、Launcher `done` extra、通知动作回归 |
| `UserRulesTest.java` | **用户自定义规则安全测试**：危险正则拦截 / 强制自测 / 50ms 熔断 / 四层发送方匹配 / 优先级 / 导入导出往返 |
| `RuleLab.java` | **规则实验台（v7 新增）**：直接加载 `rules/rules.json` 跑语料，不编译装机就能验证规则改动。改正则时先跑它，30 秒出结果；`RulesRegression` 只能跑编译进代码的那份副本 |
| `check_rules_consistency.py` | **规则三副本一致性校验**（改规则后必跑，见下）。v7 起额外校验 `dashPattern` / `alnumPattern` 两份码形正则 |
| `sms-cases.txt` | 官方规则用例集（`ID｜期望码｜短信原文`，期望为 `-` 表示反例）。**已扩到 100 条**：001–039 为原有用例（033/034 是 GitHub issue #10/#11 的真实短信，036–039 是防止码形放宽引入误判的反例）；040–075 覆盖各主流快递、各锚点词（取件码/取货码/提货码/自提码/提取码/动态取件码/取件号/取货号/提货号/收货码/取件密码/凭码）、各种码形（1 位纯数字、2 位、4 段、字母前缀、混合）；076–100 是 25 条反例（银行/订单号/验证码/会员码/优惠券/挂号单/行程单/共享单车/停车/话费/授权码…） |
| `sms-corpus-public.jsonl` | **公开脱敏语料集**（100 条，含 issue #10/#11 正例与 e-mail、订单号等反例），供研究与二次开发。由 `GenCorpus.java` 从权威集导出 |
| `GenCorpus.java` | 从 `sms-cases.txt` 导出公开语料集（码与「是否反例」以权威集为准，来源与地点取引擎实际输出） |
| `DataTest.java` | 4.0 数据层单测：保留期策略、导出/导入格式往返与坏输入、规则完整性校验 |
| `FeatureTest.java` | 4.0 新功能单测：四套配色 × 明暗的对比度不变量、每日提醒调度、历史导入解析 |
| `../tools/run_all_tests.sh` | **全量离线回归的单一入口**（11 套，431 项断言） |
| `../tools/soak.sh` | **真机浸泡测试**：反复走关键路径，盯崩溃 / ANR / 内存增长 / 本地库完整性 |

> 真机验证（识别 → 写入待办全链路）用 `tools/device-probe/probe.py`，见该目录 README。

## 一键跑完全部回归

```bash
bash tools/run_all_tests.sh
```

11 套、431 项断言，末尾给出汇总表。任何一项失败退出码非 0。

> 注意：`test/libs/org.json.jar` 必须排在 `android.jar` 前面——
> android.jar 里的 org.json 只是空壳（调用即抛 `Stub!`），
> 真实现放在这里专供离线测试，**不会打进 APK**。

## ⚠️ 改规则后必跑：一致性校验

官方规则在仓库里有**三份副本**，必须完全一致：

| 副本 | 作用 | 漏改的后果 |
|---|---|---|
| `rules/rules.json` | 远端热更源 | 用户拿不到新规则 |
| `app/assets/rules/rules.json` | APK 内置兜底 | 断网用户用旧规则 |
| `ExtractorRules.java` 的 `createDefault()` | 编译进代码的默认值 | **系统进程（通知/冻结兜底通道）永远跑旧规则** |

第三份最隐蔽：系统进程没有 `Context`，读不到前两份，只能用编译进去的这份。
漏改它时**短信通道是好的、界面完全看不出异常**，只有通知取件会悄悄漏抓。

```bash
python test/check_rules_consistency.py
```

脚本会一次性核对：字节级一致性、隐藏字符（BOM/零宽/NBSP/全角空格）、
`version` / `minAppVersionCode` / `keywordAnchors` 三处对齐情况。
退出码 `0` 表示一致，`1` 表示有问题（提交前请先修掉）。

## 环境准备

需要两样东西：

1. **JDK 17+**
2. **Android SDK 的 `android.jar`**（提供 `org.json` 与 Android API 声明）

## 运行方式

### 官方规则回归

```bash
cd test
javac -encoding UTF-8 RulesRegression.java
java RulesRegression [仓库路径] [android.jar路径]
```

两个参数可省略，默认值面向本机开发环境；其他机器请显式传入：

```bash
java RulesRegression /path/to/Xiaomi-HyperOs-pickup-code-grabber /path/to/platforms/android-34/android.jar
```

也可以用环境变量：

```bash
export PICKUP_REPO=/path/to/repo
export ANDROID_JAR=/path/to/android.jar
java RulesRegression
```

**结果**：结果写入 `reg-result.txt`，末行会输出
`TOTAL CODES: N PASS=N FAIL=0` 与 `TOTAL PLACE: N PASS=N FAIL=0`。

### 调用方白名单测试

```bash
cd test
mkdir -p out-security
javac -encoding UTF-8 -cp "$ANDROID_JAR" -d out-security \
  ../app/src/io/github/okaidev/pickupcode/CallerPolicy.java CallerPolicyTest.java
java -cp out-security io.github.okaidev.pickupcode.CallerPolicyTest
```

**预期**：末行 `TOTAL: PASS=11 FAIL=0`。

### 用户规则安全测试

`UserRulesTest` 直接引用 `UserRules`，需要先把项目源码编译到临时目录：

```bash
cd test
SRC=../app/src/io/github/okaidev/pickupcode
OUT=./out
mkdir -p $OUT

# 1) 编译项目源码（android.jar 提供 org.json 与 Android API 声明）
javac -encoding UTF-8 -nowarn -cp "$ANDROID_JAR" -d $OUT \
  $SRC/UserRules.java $SRC/Repair.java $SRC/Diagnostics.java \
  $SRC/TodoWriter.java $SRC/PickupExtractor.java $SRC/ExtractorRules.java \
  $SRC/MissedSmsStore.java $SRC/NotesBackend.java

# 2) 编译并运行测试
javac -encoding UTF-8 -nowarn -cp "$ANDROID_JAR;$OUT" -d $OUT UserRulesTest.java
java -cp "$OUT;$ANDROID_JAR" io.github.okaidev.pickupcode.UserRulesTest
```

**预期**：末行输出 `总计: PASS=45 FAIL=0`

---

## ⚠️ 给贡献者：改动源码后请同步更新编译清单

`RulesRegression.java` 里的 `NEED` 数组列出了要参与编译的源文件。
如果你新增了 `PickupExtractor` / `UserRules` / `TodoWriter` 的依赖，**记得把它加进 `NEED`**，
否则回归脚本会 `COMPILE FAIL`（这是本项目踩过的坑）。

当前依赖链：

```
ExtractorRules  ←── 无 Android 依赖
PickupExtractor ←── UserRules, ExtractorRules
UserRules       ←── Repair, Diagnostics（仅同步文件路径用）
TodoWriter      ←── PickupExtractor, NotesBackend, MissedSmsStore
NotesBackend    ←── TodoWriter
```

## 新增用例

发现新场景请补进 `sms-cases.txt`：

```
028 | 4-5-6789 | 【某驿站】凭4-5-6789到XX店取件
029 | - | 【某营销】您的订单已发货，单号123456789，详见 https://xxx.cn/a
```

- 第一列：编号（两位数递增）
- 第二列：期望取出的取件码；**反例写 `-`**
- 第三列：短信原文（**务必脱敏**：姓名 / 手机号 / 住址）

## 公开语料集

`sms-corpus-public.jsonl` 是脱敏后的公开语料，每行一个 JSON：

```json
{"sms":"...","expectCodes":["16-4-9626"],"expectSource":"菜鸟驿站","expectPlace":null,"isCounterExample":false}
```

用途：
- 二次开发时作为测试素材
- 提交自定义规则时附上你的样本（**记得自行脱敏**）
