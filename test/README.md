# 🧪 测试与回归

本目录是项目的**脱机回归测试**（不需要真机、不需要 LSPosed 环境）。
所有测试都用 `android.jar` 作 classpath 动态编译项目源码，可在普通 JVM 上运行。

## 目录内容

| 文件 | 说明 |
|---|---|
| `RulesRegression.java` | **官方规则引擎回归**：跑 `sms-cases.txt` 全部用例 + 冒烟自检 |
| `UserRulesTest.java` | **用户自定义规则安全测试**：危险正则拦截 / 强制自测 / 50ms 熔断 / 四层发送方匹配 / 优先级 / 导入导出往返 |
| `sms-cases.txt` | 官方规则用例集（`ID｜期望码｜短信原文`，期望为 `-` 表示反例） |
| `sms-corpus-public.jsonl` | **公开脱敏语料集**（18 条，正例 12 / 反例 6），供研究与二次开发 |

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
