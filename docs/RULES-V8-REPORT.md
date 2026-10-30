# 规则集 v8 报告（4.0 收口）

> 变更日期：2026-10-30　　基线：v7 → **v8**
> 唯一事实源：`rules/rules.json`（`version: 8`）
> 派生产物（构建期生成，不手工维护）：
> `app/build/generated/rules/java/.../GeneratedRules.java` 与 `app/build/generated/rules/assets/rules/rules.json`

---

## 一、为什么会有 v8

扩充特征化语料（`test/sms-cases.txt` 39 → **100 条**）时，新增了一条反例：

```
089 | - | 【某快递】您的包裹预计明天送达，取件码 33-2-1188 暂未生成
```

期望：**不提取**。实际：提取出了 `33-2-1188`。

原因不是码形规则太松，而是**语义闸门漏了一种情况**：这条短信里的码
还没生效（"暂未生成"），抓进去只会给用户凭空添一条永远用不了的待办。

### 修法：新增 `excludeTailPhrases`（可分隔的尾词短语）

原有 `excludeTailWords` 要求**紧邻**（码后面必须直接跟该词）：

```java
String tail = body.substring(end, Math.min(body.length(), end + 4));
if (tail.startsWith(w)) return true;   // 「取件码 33-2-1188 暂未生成」这里 tail 是 " 暂未生成"
```

带一个空格就不匹配了。把「暂未」塞进 `excludeTailWords` 解决不了问题，
改宽既有尾词的语义又会让别的规则跟着变松。

所以**另开一张表**：语义上属于「否定语气」的词单独放 `excludeTailPhrases`，
匹配时允许码与短语之间有空格 / 逗号 / 顿号 / 冒号。

| 字段 | 语义 | 匹配方式 |
|---|---|---|
| `excludeTailWords` | 量词、单位、时间（`号` `元` `折` `天` `个` `件` …） | **紧邻**码 |
| `excludeTailPhrases` | 否定语气短语（`暂未`） | 可有分隔符 |

改动落在四个地方（全部由 `rules/rules.json` 驱动）：

| 文件 | 改动 |
|---|---|
| `rules/rules.json` | 新增 `excludeTailPhrases: ["暂未"]`，`version` 7 → 8 |
| `app/build.gradle` | `generateRules` 多生成 `EXCLUDE_TAIL_PHRASES` |
| `ExtractorRules.java` | 新增字段 + `fromJson` 解析 + `toJson` 输出 |
| `PickupExtractor.isExcludedToken` | 新增 3.5 步判定 |
| `test/check_rules_consistency.py` | 三副本一致性校验新增该字段 |

---

## 二、顺带修掉的一个隐患：RuleLab 有一份手抄本

`test/RuleLab.java`（规则实验台，改规则后 30 秒出结果的工具）原先**手写**了一份
「把 rules.json 填进 ExtractorRules」的加载逻辑，与 App 里的
`ExtractorRules.fromJson` 各写各的。v8 新增字段时，实验台就漏了——
两处规则加载器漂移，正是本项目在规则集上反复踩的坑。

现在 RuleLab 直接复用 App 的加载器：

```java
static ExtractorRules rulesFromJson(String path) throws Exception {
    String raw = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    return ExtractorRules.fromJson(raw);
}
```

**实验台跑的就是 App 真正会跑的那份规则。**

> 附带修掉 `tools/run_all_tests.sh` 里 RuleLab 的两处 classpath：
> 编译与运行都得把 `test/libs/org.json.jar` 排在 `android.jar` 前面，
> 否则 `org.json` 用的是 android.jar 的空壳（调用即抛 `Stub!`）。

---

## 三、语料扩充（39 → 100 条）

| 区段 | 条数 | 覆盖 |
|---|---|---|
| 001–039 | 39 | 原有用例（含 GitHub issue #10/#11 的真实短信与码形反例） |
| 040–075 | 36 | 9 家主流快递（顺丰/中通/圆通/韵达/申通/京东/邮政/极兔/德邦）× 12 个锚点词（取件码/取货码/提货码/自提码/提取码/动态取件码/取件号/取货号/提货号/收货码/取件密码/凭码）× 各种码形（1 位纯数字、2 位、4 段、字母前缀 `A12345`/`B7-2211`/`CC9-4411`、混合 `1023-b-88`） |
| 076–100 | 25 | 反例：银行余额、订单号、验证码、外卖优惠、运单号、会员码、优惠券、挂号单、行程单、积分、共享单车骑行码、充电订单号、卡券、排队号、车牌号、话费余量、授权码、助记码、促销价、套餐到期…… |

扩充原则：**正例锁行为、反例锁闸门**。新加的反例不是凭空造的，
而是日常短信里真实会收到、但**绝不能**当取件码的东西——
这正是「宁可漏抓也不能误抓」的具体化。

公开语料集 `test/sms-corpus-public.jsonl` 同步扩到 100 条，
由 `test/GenCorpus.java` 从权威集导出（码与「是否反例」以权威集为准）。

---

## 四、回归结果

| 套件 | 结果 |
|---|---|
| RulesRegression（内置规则副本） | 码 **100/100**、地点 13/13 |
| RuleLab（`rules/rules.json` 直跑） | **100/100** |
| 规则三副本一致性 | OK · version=8 · anchors=13 |

全量离线回归：**11 套、431 项断言全绿**（`bash tools/run_all_tests.sh`）。

真机（REDMI K90 Pro Max / HyperOS 4.0 / Android 17）：
`PickupExtractor: loaded custom rules v8`，首页显示「官方规则集 v8」，无崩溃。

---

## 五、还没做的（明确记下，不假装做了）

- **真正的签名**：目前是「双镜像字节一致 + 同版本 SHA-256 锚定」，
  能挡住内容损坏、单镜像被劫持、同版本号偷换；
  挡不住两个镜像同时被攻破。要挡住那个需要 Ed25519 + 内置公钥，属 5.0 范围。
- **镜像可达性**：本机网络无法访问 jsDelivr 与 raw.githubusercontent，
  所以「双镜像都取不到」时的路径真机只验证了「如实提示网络不可用」，
  没验证过「只取到一个镜像时降级放行」的完整流程（单测覆盖了判定逻辑）。
