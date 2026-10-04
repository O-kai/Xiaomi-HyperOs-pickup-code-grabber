package io.github.okaidev.pickupcode;

import java.util.List;

/**
 * 语义排除回归测试（v3.1.0 新增）
 *
 * 【为什么需要这个测试】
 * 2026-10-04 的全量代码审计发现一个结构性缺陷：
 * TierA（关键词锚点）与 TierB（凭/出示）此前只做 validCode（码形检查），
 * 完全跳过了 isExcludedToken（语义排除），导致：
 *
 *   「…3号柜284号格口，取件码284」   → 误取 284（其实是柜/格口编号）
 *   「…取件码500元」                 → 误取 500（金额）
 *   「…尾号5678，取件码…」          → 误取（其实是卡号尾号）
 *
 * 而锚点短信恰恰是用户投诉「抓错码」的主要来源——它最自信，也最容易抓错。
 * 原来的 21 条冒烟用例里没有任何一条是「锚点 + 语义噪声」，所以完全测不到。
 *
 * 【本测试的职责】
 * 一边锁住「该拦的拦住」，一边锁住「不该拦的别误伤」——
 * 语义排除如果加得太狠，会把真取件码也滤掉，那是更严重的故障。
 *
 * 运行：本文件随 test/RulesRegression 同批编译，见 test/README.md
 */
public class TierExclusionTest {

    private static int pass = 0;
    private static int fail = 0;

    private static void expect(String label, String body, String want) {
        List<String> got = PickupExtractor.extract(null, body);
        String g = got.isEmpty() ? "(空)" : String.join(";", got);
        if (g.equals(want)) {
            pass++;
            System.out.println("  ok   " + label + "  -> " + g);
        } else {
            fail++;
            System.out.println("  FAIL " + label);
            System.out.println("       期望=" + want);
            System.out.println("       实际=" + g);
        }
    }

    public static void main(String[] args) {
        PickupExtractor.init(null);
        run();
    }

    public static void run() {
        System.out.println("== 语义排除回归（TierA/TierB 不得绕过） ==");

        System.out.println("-- 尾词排除（码后跟 号/楼/元/单…）应被拦 --");
        expect("格口编号", "【菜鸟】包裹已到3号柜284号格口，取件码284号", "(空)");
        expect("楼层", "【某】取件码8888楼", "(空)");
        expect("金额", "【某】取件码500元", "(空)");
        expect("件数", "【某】取件码A1234单", "(空)");

        System.out.println("-- 头词排除（码前是 订单/尾号/第…）应被拦 --");
        expect("订单号", "【某商城】您的订单号123123123已发货，取件码待定", "(空)");
        expect("卡号尾号", "【某】尾号5678，取件码请短信获取", "(空)");
        expect("序号", "【某】第88，取件码请查收", "(空)");

        System.out.println("-- 掩码手机号 --");
        expect("掩码号", "您的手机号159****6739，取件码请短信获取", "(空)");

        System.out.println("-- 真码必须仍然抓到（防修复过头，这是回归红线） --");
        expect("标准锚点", "【菜鸟驿站】取件码22-2-3579，请到XX小区取件", "22-2-3579");
        expect("四段横线", "【邮侠】凭取件码1681-5191到x镇x村xx号菜鸟驿站2号柜取件", "1681-5191");
        expect("字母码", "【某】取件码A88123已生成", "A88123");
        expect("提货码", "【递管家】您的快递:*37240已到悦陇府洋房门口快递柜，请用提货码7652-5845取包裹", "7652-5845");
        expect("取货码", "【妈妈驿站】取货码552577至同兴冠寓3号楼1号兔喜快递柜取件", "552577");
        expect("凭+码", "【A驿站】凭552577至同城某店取件", "552577");
        expect("多码簇", "【菜鸟】取件码16-4-9626、15-3-2194、16-3-0906，请一并取出", "16-4-9626;15-3-2194;16-3-0906");
        expect("字母+横线", "【D】取件码D4-7048在柜", "D4-7048");
        expect("M-2 形态", "取件码M-2-5341请查收", "M-2-5341");
        expect("尾随取件", "【兔喜】取件码 22-2-3579 已到丰巢柜，请速到A区取件", "22-2-3579");
        expect("纯数字码", "【丰巢】您的取件码72778已生成", "72778");
        expect("长数字码", "【某】取件码267961已生成", "267961");
        expect("无锚点靠上下文", "【兔喜生活】请凭552577至同兴冠寓3号楼1号兔喜快递柜取件", "552577");

        System.out.println("   语义排除: PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }
}