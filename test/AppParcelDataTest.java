package io.github.okaidev.pickupcode;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/** Synthetic schema fixtures: no phone numbers, addresses or real parcel codes. */
public final class AppParcelDataTest {
    private static int passed;

    private static void check(String name, boolean condition) {
        if (!condition) throw new AssertionError(name);
        passed++;
    }

    private static JSONObject parcel(String status, String code, String station) throws Exception {
        return new JSONObject().put("logisticsStatus", status).put("packageStation",
                new JSONObject().put("authCode", code).put("stationName", station));
    }

    private static JSONObject homeItem(String kind, String group, JSONObject fields) throws Exception {
        return new JSONObject().put("bizzType", kind).put("groupId", group)
                .put("packageData", new JSONObject().put("tag", kind).put("data", fields).toString());
    }

    public static void main(String[] args) throws Exception {
        JSONObject root = new JSONObject().put("packages", new JSONArray()
                .put(parcel("AGENT_SIGN", "1-2-3456", "测试驿站甲"))
                .put(parcel("AGENT_SIGN", "654321", "测试驿站乙"))
                .put(parcel("SIGN", "111111", "已签收测试站"))
                .put(parcel("TRANSPORT", "222222", "运输测试站"))
                .put(new JSONObject().put("authCode", "987654")));
        List<AppParcelData.Event> cn = AppParcelData.cainiaoResponse(root.toString());
        check("only parcels waiting at station", cn.size() == 2);
        check("first parcel code", cn.get(0).code.equals("1-2-3456"));
        check("first parcel place", cn.get(0).place.equals("测试驿站甲"));
        check("second parcel keeps own place", cn.get(1).place.equals("测试驿站乙"));
        check("hidden auth code excluded", AppParcelData.cainiaoResponse(new JSONObject()
                .put("package", parcel("AGENT_SIGN", "345678", "测试站").put("packageStation",
                        new JSONObject().put("authCode", "345678").put("showAuthCode", false)))
                .toString()).isEmpty());
        check("OAuth authCode is not parcel code", AppParcelData.cainiaoResponse(
                "{\"login\":{\"authCode\":\"123456\",\"stationName\":\"测试\"}}").isEmpty());
        check("unclassified station is not pending", AppParcelData.cainiaoResponse(
                "{\"packageStation\":{\"authCode\":\"123456\"}}").isEmpty());

        JSONObject pickup = new JSONObject().put("pageState", 2).put("spotsViewModels", new JSONArray()
                .put(new JSONObject().put("title", "测试取件点").put("address", "测试位置")
                        .put("packageItems", new JSONArray()
                                .put(new JSONObject().put("title", "A12-3456"))
                                .put(new JSONObject().put("title", "暂无取件码"))
                                .put(new JSONObject().put("title", "111111").put("isSigning", 1)))));
        List<AppParcelData.Event> old = AppParcelData.cainiaoPickup(pickup.toString());
        check("pickup data without notification", old.size() == 1);
        check("pickup data code", old.get(0).code.equals("A12-3456"));
        check("pickup data station context", old.get(0).place.equals("测试取件点 · 测试位置"));
        check("failed pickup load ignored", AppParcelData.cainiaoPickup(pickup.put("pageState", 3).toString()).isEmpty());

        JSONArray homeItems = new JSONArray()
                .put(homeItem("STATION_INFO", "g1", new JSONObject().put("stationTitle", "测试站甲")))
                .put(homeItem("PKG_INFO_ITEM", "g1", new JSONObject().put("logisticStatus", "AGENT_SIGN")
                        .put("validPackage", false).put("richTitle", new JSONObject().put("attributedNodes", new JSONArray()
                                .put(new JSONObject().put("text", "A12-"))
                                .put(new JSONObject().put("text", "3456"))))))
                .put(homeItem("STATION_INFO", "g1", new JSONObject().put("stationTitle", "测试站乙").put("stationSubtitle", "测试位置")))
                .put(homeItem("PKG_INFO_ITEM", "g1", new JSONObject().put("logisticStatus", "AGENT_SIGN").put("title", "1-2-5678")))
                .put(homeItem("PKG_INFO_ITEM", "missing", new JSONObject().put("logisticStatus", "AGENT_SIGN").put("title", "123456")))
                .put(homeItem("PKG_INFO_ITEM", "g1", new JSONObject().put("logisticStatus", "SIGN").put("title", "456789")))
                .put(homeItem("AD_CARD", "g1", new JSONObject().put("logisticStatus", "AGENT_SIGN").put("title", "345678")))
                .put(homeItem("PKG_INFO_ITEM", "g1", new JSONObject().put("logisticStatus", "AGENT_SIGN").put("title", "12**56")));
        String homepage = new JSONObject().put("data", new JSONObject().put("packageDataItemArray", homeItems)).toString();
        List<AppParcelData.Event> home = AppParcelData.cainiaoHomepage(homepage);
        check("homepage pending parcels only", home.size() == 3);
        check("homepage rich title code", home.get(0).code.equals("A12-3456"));
        check("homepage first station with shared group", home.get(0).place.equals("测试站甲"));
        check("homepage second station with shared group", home.get(1).place.equals("测试站乙 · 测试位置"));
        check("homepage missing station never inherits previous", home.get(2).place.equals("—"));
        check("homepage requires data envelope", AppParcelData.cainiaoHomepage(new JSONObject().put("packageDataItemArray", homeItems).toString()).isEmpty());
        check("homepage oversized payload", AppParcelData.cainiaoHomepage(" ".repeat(AppParcelData.MAX_PAYLOAD + 1)).isEmpty());

        JSONObject result = new JSONObject().put("status", 3).put("code", "778899").put("address", "测试柜");
        JSONObject pdd = new JSONObject().put("result", result);
        List<AppParcelData.Event> events = AppParcelData.pinduoduo(pdd.toString());
        check("PDD cabinet code", events.size() == 1 && events.get(0).code.equals("778899"));
        check("PDD address", events.get(0).place.equals("测试柜"));
        for (int status : new int[]{0, 1, 2, 4, 99}) {
            result.put("status", status);
            check("PDD non-code state " + status, AppParcelData.pinduoduo(pdd.toString()).isEmpty());
        }
        result.put("status", 3).put("showQrCodePickUp", true);
        check("PDD QR payload excluded", AppParcelData.pinduoduo(pdd.toString()).isEmpty());
        check("missing PDD result", AppParcelData.pinduoduo("{\"code\":\"123456\"}").isEmpty());

        for (String code : new String[]{"", "12**56", "暂无取件码", "https://example.test/1234", "13800000000", "A--123", "ABC", "1-2-3-4-5-6"}) {
            check("invalid code " + code, !AppParcelData.validCode(code));
        }
        for (String code : new String[]{"1", "12345678", "A88123", "1-2-3-4567"}) {
            check("valid code " + code, AppParcelData.validCode(code));
        }
        check("malformed JSON", AppParcelData.cainiaoResponse("bad").isEmpty());
        check("oversized JSON", AppParcelData.cainiaoResponse(" ".repeat(AppParcelData.MAX_PAYLOAD + 1)).isEmpty());
        JSONArray many = new JSONArray();
        for (int i = 0; i < 120; i++) many.put(parcel("AGENT_SIGN", "1-2-" + i, "测试站"));
        check("bounded event count", AppParcelData.cainiaoResponse(new JSONObject().put("packages", many).toString()).size() == 100);
        check("sanitize place", AppParcelData.cleanPlace("测试\n位置").equals("测试 位置"));

        check("Cainiao caller matches", CallerPolicy.matchesAppParcelPackages(AppParcelData.CAINIAO, new String[]{AppParcelData.CAINIAO}));
        check("PDD caller matches", CallerPolicy.matchesAppParcelPackages(AppParcelData.PINDUODUO, new String[]{AppParcelData.PINDUODUO}));
        check("cross-app spoof rejected", !CallerPolicy.matchesAppParcelPackages(AppParcelData.CAINIAO, new String[]{AppParcelData.PINDUODUO}));
        check("foreign caller rejected", !CallerPolicy.matchesAppParcelPackages(AppParcelData.CAINIAO, new String[]{"example.fake"}));
        check("unsupported app rejected", !CallerPolicy.matchesAppParcelPackages("example.fake", new String[]{"example.fake"}));
        check("missing UID packages rejected", !CallerPolicy.matchesAppParcelPackages(AppParcelData.CAINIAO, null));
        check("target apps still denied SMS endpoint", !CallerPolicy.isTrustedSmsPackage(AppParcelData.CAINIAO)
                && !CallerPolicy.isTrustedSmsPackage(AppParcelData.PINDUODUO));
        System.out.println("AppParcelDataTest TOTAL: PASS=" + passed + " FAIL=0");
    }
}
