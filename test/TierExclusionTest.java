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

        // v9：纯状态通知（电商只说「有件 / 在送」，不给取件凭据）。
        // 样本来自真机错题本，全部是拼多多通知；以前它们会被当成「疑似漏抓」记下来。
        System.out.println("-- v9 纯状态通知：含特征词但没凭据，整条不是取件短信 --");
        expect("拼多多·待取件提醒",
                "商品待取件提醒\n您的商品已存放至菜鸟驿站代收点，请凭手机号及时取件，今日共2个快递待取。", "(空)");
        expect("拼多多·发货通知",
                "订单发货通知\n神经蛙 ，您购买的商品已通过申通快递发货。", "(空)");
        expect("拼多多·派送通知",
                "商品派送通知\n神经蛙 ，您的商品正由圆通快递派送中。 请注意查收。服饰洗涤后不支持无理由退货。", "(空)");
        expect("拼多多·超时未取",
                "商品待取件提醒\n您的商品在代收点已超1天未取。若取件遇到问题请联系快递员，今日共1个快递待取。", "(空)");

        // 这一条是上面那条规则的护栏：只排除「商品待取件提醒」这种电商标题，
        // 不能把驿站真实取件短信里更常见的「待取件提醒」一起误杀。
        System.out.println("-- v9 反向保护：驿站真实「待取件提醒」不能被误杀 --");
        expect("菜鸟·待取件提醒",
                "【菜鸟驿站】待取件提醒：您的包裹已到江南绿城西门，取件码 12-3-4567", "12-3-4567");
        expect("丰巢·待取提醒",
                "【丰巢】待取件提醒：您的包裹已到丰巢智能柜，取件码 33-2-1188", "33-2-1188");

        // 错题本的入口用的是 hasFeatureWords（比 extract 更宽），
        // 纯状态通知必须在这一层就被挡住，否则错题本还是会被灌满。
        System.out.println("-- v9 错题本门禁：纯状态通知不得进入「疑似漏抓」 --");
        expectNotPickup("拼多多·待取件提醒",
                "商品待取件提醒\n您的商品已存放至菜鸟驿站代收点，请凭手机号及时取件，今日共2个快递待取。");
        expectNotPickup("拼多多·发货通知",
                "订单发货通知\n神经蛙 ，您购买的商品已通过申通快递发货。");
        expectNotPickup("拼多多·派送通知",
                "商品派送通知\n神经蛙 ，您的商品正由圆通快递派送中。 请注意查收。");
        expectNotPickup("拼多多·超时未取",
                "商品待取件提醒\n您的商品在代收点已超1天未取。若取件遇到问题请联系快递员，今日共1个快递待取。");
        expectPickup("真实取件短信",
                "【菜鸟驿站】待取件提醒：您的包裹已到江南绿城西门，取件码 12-3-4567");

        System.out.println("   语义排除: PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }

    /** hasFeatureWords 必须为 false（纯状态通知连错题本都不该进） */
    private static void expectNotPickup(String label, String body) {
        boolean got = PickupExtractor.hasFeatureWords(null, body);
        if (!got) {
            pass++;
            System.out.println("  ok   " + label + "  -> 非取件");
        } else {
            fail++;
            System.out.println("  FAIL " + label + " 期望=非取件 实际=被当成取件短信");
        }
    }

    /** hasFeatureWords 必须为 true（真实取件短信不能被误杀） */
    private static void expectPickup(String label, String body) {
        boolean got = PickupExtractor.hasFeatureWords(null, body);
        if (got) {
            pass++;
            System.out.println("  ok   " + label + "  -> 取件短信");
        } else {
            fail++;
            System.out.println("  FAIL " + label + " 期望=取件短信 实际=非取件");
        }
    }
}