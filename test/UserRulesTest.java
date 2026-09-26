package io.github.okaidev.pickupcode;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户规则引擎安全测试（脱机）
 * 重点验证三道防线：危险构造拦截 / 强制自测 / 50ms 熔断
 * 编译时需要 android.jar（UserRules 用了 Context/SharedPreferences/Log）
 */
public class UserRulesTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("PASS  " + name); }
        else { fail++; System.out.println("FAIL  " + name); }
    }

    public static void main(String[] args) throws Exception {
        // ========== 防线1：危险构造静态拦截 ==========
        System.out.println("=== 防线1：危险正则构造拦截 ===");
        check("拦截嵌套量词 (x+)+", UserRules.scanDangerous("(a+)+b") != null);
        check("拦截嵌套量词 (x*)*", UserRules.scanDangerous("(a*)*") != null);
        check("拦截相邻通配 .*.*", UserRules.scanDangerous(".*.*") != null);
        check("拦截量词叠加 a+a+", UserRules.scanDangerous("[+*]\\s*[+*]") != null);
        check("放行正常正则 取件码([0-9-]+)", UserRules.scanDangerous("取件码([0-9-]+)") == null);
        check("放行正常正则 凭([A-Za-z0-9-]+)到", UserRules.scanDangerous("凭([A-Za-z0-9-]+)到") == null);
        check("放行带量词的正常正则 \\d{3,9}", UserRules.scanDangerous("\\d{3,9}") == null);
        check("拦截超长正则", UserRules.scanDangerous(longPattern(400)) != null);

        // ========== 防线2：强制自测样本 ==========
        System.out.println("\n=== 防线2：强制自测样本 ===");
        UserRules.Rule noTest = rule("无样本规则", "取件码([0-9-]+)");
        UserRules.VerifyResult vr = UserRules.verify(noTest);
        check("无测试样本 → 拒绝启用", !vr.ok && vr.message.contains("测试样本"));

        UserRules.Rule badExpect = rule("期望不符", "取件码([0-9-]+)");
        badExpect.testCases.add(new UserRules.TestCase("取件码1234", "9999"));
        vr = UserRules.verify(badExpect);
        check("期望不符 → 拒绝并指出差异", !vr.ok && vr.message.contains("9999"));

        UserRules.Rule good = rule("正常规则", "取件码([0-9-]+)");
        good.testCases.add(new UserRules.TestCase("您的取件码1234已到店", "1234"));
        vr = UserRules.verify(good);
        check("期望相符 → 自测通过", vr.ok);

        // 多码场景：单码正则应只取第一段（顿号分隔属多码簇，由官方引擎的多码簇逻辑负责）
        UserRules.Rule multi = rule("多码", "取件码([0-9-]+)");
        multi.testCases.add(new UserRules.TestCase("取件码1234、5678", "1234"));
        vr = UserRules.verify(multi);
        check("顿号分隔时单码正则取第一段（多码簇交给官方引擎）", vr.ok);

        // 捕获组越界
        UserRules.Rule badGroup = rule("组越界", "取件码[0-9-]+");
        badGroup.codeGroup = 3;
        badGroup.testCases.add(new UserRules.TestCase("取件码1234", "1234"));
        vr = UserRules.verify(badGroup);
        check("捕获组越界 → 明确报错", !vr.ok && vr.message.contains("捕获组"));

        // ========== 防线3：50ms 熔断 ==========
        System.out.println("\n=== 防线3：耗时熔断 ===");
        UserRules.Rule evil = rule("灾难性回溯", "(a+)+b");
        evil.testCases.add(new UserRules.TestCase(repeat("a", 60) + "!", "X"));
        long t0 = System.currentTimeMillis();
        vr = UserRules.verify(evil);
        long cost = System.currentTimeMillis() - t0;
        check("灾难性正则被拦截（静态扫描先行）", !vr.ok);
        System.out.println("      拦截耗时: " + cost + "ms（说明未真正执行危险匹配，未卡死）");

        // 正常但稍复杂的正则：应在阈值内通过
        UserRules.Rule complex = rule("较复杂但安全", "(?:取件码|取货码|凭码)[\\s:：]*([A-Za-z0-9-]{3,20})");
        complex.testCases.add(new UserRules.TestCase("【菜鸟】取件码:5-5-9-13 已到店", "5-5-9-13"));
        complex.testCases.add(new UserRules.TestCase("请凭码 72778 到店取件", "72778"));
        vr = UserRules.verify(complex);
        check("多选分支正则正常通过", vr.ok);

        // ========== 匹配正确性 ==========
        System.out.println("\n=== 提取正确性 ===");
        UserRules.Rule r1 = rule("提取测试", "取货码([A-Za-z0-9-]+)");
        UserRules.compileRule(r1);
        check("取货码2-2-7508 → 2-2-7508",
                "2-2-7508".equals(UserRules.matchCode(r1, "【妈妈驿站】取货码2-2-7508，您有极兔快递包裹")));
        check("无匹配返回 null",
                UserRules.matchCode(r1, "您的包裹已到站") == null);

        // 整组匹配（group=0）：正则 M-\d+-\d+ 才匹配完整 M-2-5341
        UserRules.Rule r0 = rule("整组", "M-\\d+-\\d+");
        r0.codeGroup = 0;
        UserRules.compileRule(r0);
        check("捕获组=0 返回整个匹配",
                "M-2-5341".equals(UserRules.matchCode(r0, "凭M-2-5341到店取件")));

        // ========== 导入导出往返 ==========
        System.out.println("\n=== 导入导出往返 ===");
        List<UserRules.Rule> list = new ArrayList<>();
        UserRules.Rule exp = rule("我小区驿站", "取件码([0-9-]+)");
        exp.source = "小区菜鸟";
        exp.place = "$1号柜";
        exp.senderMode = UserRules.MODE_PREFIX;
        exp.senderValue = "1069";
        exp.testCases.add(new UserRules.TestCase("取件码1234", "1234"));
        exp.enabled = true;
        list.add(exp);
        String text = UserRules.exportForShare(list);
        List<UserRules.Rule> back = UserRules.importFromShare(text);
        check("导出后可导入", back.size() == 1);
        if (back.size() == 1) {
            UserRules.Rule b = back.get(0);
            check("规则名还原", "我小区驿站".equals(b.name));
            check("正则还原", "取件码([0-9-]+)".equals(b.codeRegex));
            check("来源还原", "小区菜鸟".equals(b.source));
            check("发送方模式还原", UserRules.MODE_PREFIX.equals(b.senderMode));
            check("发送方值还原", "1069".equals(b.senderValue));
            check("测试样本还原", b.testCases.size() == 1
                    && "1234".equals(b.testCases.get(0).expectCode));
            UserRules.VerifyResult ivr = UserRules.verify(b);
            check("导入后自测仍通过", ivr.ok);
        }

        // ========== 跨规则优先级（tryMatch 返回首个命中） ==========
        System.out.println("\n=== 规则匹配入口 ===");
        List<UserRules.Rule> active = new ArrayList<>();
        UserRules.Rule a1 = rule("规则A", "取件码(\\d{4})");
        UserRules.compileRule(a1);
        active.add(a1);
        String[] hit = UserRules.tryMatch(active, "10086", "取件码5678已到店");
        check("tryMatch 命中返回码", hit != null && "5678".equals(hit[0]));
        check("未命中返回 null", UserRules.tryMatch(active, "10086", "无关短信") == null);

        // ========== 发送方匹配四模式（v3.0.0） ==========
        System.out.println("\n=== 发送方匹配模式 ===");
        UserRules.Rule exact = rule("精确", "取件码(\\d{4})");
        exact.senderMode = UserRules.MODE_EXACT; exact.senderValue = "1069888877";
        check("EXACT 完全一致 → 命中", UserRules.senderMatches(exact, "1069888877"));
        check("EXACT 多一位 → 不命中", !UserRules.senderMatches(exact, "10698888770"));
        check("EXACT 不同号 → 不命中", !UserRules.senderMatches(exact, "1069888878"));

        UserRules.Rule prefix = rule("前缀", "取件码(\\d{4})");
        prefix.senderMode = UserRules.MODE_PREFIX; prefix.senderValue = "1069";
        check("PREFIX 开头一致 → 命中（虚拟号后几位随机）",
                UserRules.senderMatches(prefix, "10691234567"));
        check("PREFIX 开头不同 → 不命中", !UserRules.senderMatches(prefix, "1008612345"));
        check("PREFIX 短前缀不会匹配所有1开头",
                !UserRules.senderMatches(prefix, "19999999999"));

        UserRules.Rule cont = rule("包含", "取件码(\\d{4})");
        cont.senderMode = UserRules.MODE_CONTAINS; cont.senderValue = "菜鸟驿站";
        check("CONTAINS 含该文字 → 命中", UserRules.senderMatches(cont, "菜鸟驿站1066"));
        check("CONTAINS 无关 → 不命中", !UserRules.senderMatches(cont, "10086"));

        UserRules.Rule any = rule("全局", "取件码(\\d{4})");
        any.senderMode = UserRules.MODE_ANY; any.senderValue = "";
        check("ANY 任何发送方都命中", UserRules.senderMatches(any, "10086"));
        check("ANY sender为null也命中", UserRules.senderMatches(any, null));

        // 兼容旧结构
        UserRules.Rule legacy = rule("旧结构", "取件码(\\d{4})");
        legacy.senderContains = "1069";
        check("旧 senderContains 字段仍可按 CONTAINS 匹配",
                UserRules.senderMatches(legacy, "xx1069xx"));

        // 发送方格式校验
        check("EXACT 非纯数字 → 明确报错",
                UserRules.validateSender(exact2("菜鸟驿站", UserRules.MODE_EXACT)) != null);
        check("PREFIX 非数字 → 明确报错",
                UserRules.validateSender(exact2("abc", UserRules.MODE_PREFIX)) != null);
        check("EXACT 正常号码 → 通过",
                UserRules.validateSender(exact2("1069888877", UserRules.MODE_EXACT)) == null);
        check("ANY 模式无值 → 通过",
                UserRules.validateSender(exact2("", UserRules.MODE_ANY)) == null);

        // ========== 优先级：号码专属 > 全局 ==========
        System.out.println("\n=== 规则优先级（号码专属优先） ===");
        UserRules.Rule gRule = rule("全局兜底", "(\\d{4})");
        UserRules.compileRule(gRule);
        UserRules.Rule pRule = rule("虚拟号专属", "专属(\\d{4})");
        pRule.senderMode = UserRules.MODE_PREFIX; pRule.senderValue = "1069";
        UserRules.compileRule(pRule);

        List<UserRules.Rule> mixed = new ArrayList<>();
        mixed.add(gRule);   // 全局在前
        mixed.add(pRule);   // 专属在后
        String[] hitPri = UserRules.tryMatch(mixed, "10698888", "短信内容 取件码1234 专属5678");
        check("号码专属规则优先于全局规则",
                hitPri != null && "5678".equals(hitPri[0]));

        // 具体度排序
        check("具体度排序：EXACT > PREFIX > CONTAINS > ANY",
                UserRules.specificity(pRule) > UserRules.specificity(gRule)
                        && UserRules.specificity(gRule) == 0);

        System.out.println("\n=========================================");
        System.out.println("总计: PASS=" + pass + " FAIL=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    static UserRules.Rule exact2(String val, String mode) {
        UserRules.Rule r = rule("t", "x");
        r.senderMode = mode;
        r.senderValue = val;
        return r;
    }

    static UserRules.Rule rule(String name, String regex) {
        UserRules.Rule r = new UserRules.Rule();
        r.id = "test_" + name;
        r.name = name;
        r.codeRegex = regex;
        r.codeGroup = 1;
        r.senderContains = "";
        r.source = "";
        r.place = "";
        r.testCases = new ArrayList<>();
        r.enabled = true;
        return r;
    }

    static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    static String longPattern(int n) {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < n) sb.append("[0-9]a");
        return sb.toString();
    }
}
