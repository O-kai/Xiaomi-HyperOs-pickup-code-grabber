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

        JSONArray stations = new JSONArray()
                .put(new JSONObject().put("address", "测试点甲").put("combine_track_list", new JSONArray()
                        .put(new JSONObject().put("pick_code", "2-3-4567"))
                        .put(new JSONObject().put("pick_code", "176****1234"))
                        .put(new JSONObject().put("pick_code", "暂无取件码"))))
                .put(new JSONObject().put("address", "测试点乙").put("combine_track_list", new JSONArray()
                        .put(new JSONObject().put("pick_code", "A12345"))))
                .put(new JSONObject().put("combine_track_list", new JSONArray()
                        .put(new JSONObject().put("pick_code", "123456"))))
                .put(new JSONObject().put("pick_code", "654321"));
        JSONObject trackbox = new JSONObject().put("success", true)
                .put("result", new JSONObject().put("combine_list", stations));
        List<AppParcelData.Event> pending = AppParcelData.pinduoduoTrackbox(trackbox.toString());
        check("PDD assistant pickup list excludes masks and non-parcel fields", pending.size() == 3);
        check("PDD assistant first station", pending.get(0).code.equals("2-3-4567") && pending.get(0).place.equals("测试点甲"));
        check("PDD assistant second station", pending.get(1).code.equals("A12345") && pending.get(1).place.equals("测试点乙"));
        check("PDD assistant missing address is unknown", pending.get(2).place.equals("—"));
        check("PDD assistant failed response", AppParcelData.pinduoduoTrackbox(trackbox.put("success", false).toString()).isEmpty());
        check("PDD assistant requires success", AppParcelData.pinduoduoTrackbox(new JSONObject().put("result", trackbox.getJSONObject("result")).toString()).isEmpty());
        check("PDD assistant wrong schema", AppParcelData.pinduoduoTrackbox("{\"success\":true,\"result\":{\"code\":\"123456\"}}").isEmpty());
        check("PDD assistant malformed", AppParcelData.pinduoduoTrackbox("bad").isEmpty());
        check("PDD assistant payload limit", AppParcelData.pinduoduoTrackbox(" ".repeat(AppParcelData.MAX_PAYLOAD + 1)).isEmpty());
        JSONArray manyTracks = new JSONArray();
        for (int i = 0; i < 120; i++) manyTracks.put(new JSONObject().put("pick_code", "3-4-" + i));
        check("PDD assistant event limit", AppParcelData.pinduoduoTrackbox(new JSONObject().put("success", true)
                .put("result", new JSONObject().put("combine_list", new JSONArray().put(new JSONObject()
                        .put("combine_track_list", manyTracks)))).toString()).size() == 100);

        JSONObject codeTrack = new JSONObject().put("status_type", 400).put("pick_code", "取件码 2-3-4567");
        JSONObject expressList = new JSONObject().put("success", true).put("result", new JSONObject()
                .put("track_info_list", new JSONArray().put(codeTrack)
                        .put(new JSONObject().put("status_type", 400).put("pick_code", "取件码：A12345"))
                        .put(new JSONObject().put("status_type", 400).put("pick_code", "取件手机号 176****1234"))
                        .put(new JSONObject().put("status_type", 400).put("pick_code", "取件手机号 1234"))
                        .put(new JSONObject().put("status_type", 500).put("pick_code", "取件码 654321"))
                        .put(new JSONObject().put("pick_code", "取件码 123456"))));
        List<AppParcelData.Event> express = AppParcelData.pinduoduoList(expressList.toString());
        check("PDD list pending codes only", express.size() == 2);
        check("PDD list strips exact code prefix", express.get(0).code.equals("2-3-4567"));
        check("PDD list accepts colon", express.get(1).code.equals("A12345"));
        check("PDD list never guesses place from description", express.get(0).place.equals("—"));
        for (int state : new int[]{0, 100, 200, 300, 500, 999}) {
            codeTrack.put("status_type", state);
            check("PDD list excludes state " + state, AppParcelData.pinduoduoList(new JSONObject().put("success", true)
                    .put("result", new JSONObject().put("track_info_list", new JSONArray().put(codeTrack))).toString()).isEmpty());
        }
        check("PDD list failed response", AppParcelData.pinduoduoList(expressList.put("success", false).toString()).isEmpty());
        check("PDD list malformed", AppParcelData.pinduoduoList("bad").isEmpty());
        check("PDD list payload limit", AppParcelData.pinduoduoList(" ".repeat(AppParcelData.MAX_PAYLOAD + 1)).isEmpty());
        check("PDD list response limit", AppParcelData.pinduoduoList(new JSONObject().put("success", true)
                .put("result", new JSONObject().put("track_info_list", manyTracks)).toString()).isEmpty());
        for (int i = 0; i < manyTracks.length(); i++) manyTracks.getJSONObject(i).put("status_type", 400);
        check("PDD list event limit", AppParcelData.pinduoduoList(new JSONObject().put("success", true)
                .put("result", new JSONObject().put("track_info_list", manyTracks)).toString()).size() == 100);
        stations.getJSONObject(0).getJSONArray("combine_track_list").getJSONObject(0).put("pick_code", "取件码 2-3-4567");
        check("PDD cabinet list also strips code prefix", AppParcelData.pinduoduoTrackbox(trackbox.put("success", true).toString()).get(0).code.equals("2-3-4567"));

        // A pending row is not enough: only the API's explicit, visible code field is usable.
        for (Object value : new Object[]{JSONObject.NULL, "", "  ", "暂无取件码", "取件码：",
                "取件手机号 1234", "176****1234", "13800000000", true, 1234, 12.34,
                new JSONObject().put("code", "123456"), new JSONArray().put("123456")}) {
            JSONObject row = new JSONObject().put("status_type", 400).put("pick_code", value)
                    .put("phone", "1234").put("tracking_number", "123456")
                    .put("description", "请凭取件码 6-7-8901 到测试驿站取件");
            check("PDD mixed list rejects absent/unusable code " + value,
                    AppParcelData.pinduoduoList(new JSONObject().put("success", true)
                            .put("result", new JSONObject().put("track_info_list", new JSONArray().put(row)))
                            .toString()).isEmpty());
            check("PDD cabinet list rejects absent/unusable code " + value,
                    AppParcelData.pinduoduoTrackbox(new JSONObject().put("success", true)
                            .put("result", new JSONObject().put("combine_list", new JSONArray()
                                    .put(new JSONObject().put("combine_track_list", new JSONArray().put(row)))))
                            .toString()).isEmpty());
            check("PDD detail rejects absent/unusable code " + value,
                    AppParcelData.pinduoduo(new JSONObject().put("result", new JSONObject()
                            .put("status", 3).put("code", value)).toString()).isEmpty());
        }
        JSONObject noCode = new JSONObject().put("status_type", 400).put("code", "123456")
                .put("phone", "1234").put("description", "取件码 6-7-8901");
        check("PDD list never infers a missing pick_code", AppParcelData.pinduoduoList(new JSONObject()
                .put("success", true).put("result", new JSONObject()
                        .put("track_info_list", new JSONArray().put(noCode))).toString()).isEmpty());
        check("PDD explicit four digit code is still usable", AppParcelData.pinduoduoList(new JSONObject()
                .put("success", true).put("result", new JSONObject().put("track_info_list", new JSONArray()
                        .put(new JSONObject().put("status_type", 400).put("pick_code", "1234"))))
                .toString()).get(0).code.equals("1234"));

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
        JSONObject pickupSite = new JSONObject().put("status", 3).put("code", "12-3-4567")
                .put("address", "").put("cnInOutBound", new JSONObject().put("address", "  测试驿站 · 测试路  "));
        check("PDD cabinet falls back to explicit cnInOutBound address", "测试驿站 · 测试路".equals(
                AppParcelData.pinduoduo(new JSONObject().put("result", pickupSite).toString()).get(0).place));
        pickupSite.put("address", "主取件站");
        check("PDD cabinet primary site address wins", "主取件站".equals(
                AppParcelData.pinduoduo(new JSONObject().put("result", pickupSite).toString()).get(0).place));
        pickupSite.put("address", JSONObject.NULL);
        check("PDD null address still uses nested pickup site", "测试驿站 · 测试路".equals(
                AppParcelData.pinduoduo(new JSONObject().put("result", pickupSite).toString()).get(0).place));
        pickupSite.put("cnInOutBound", new JSONObject().put("address", 1234));
        check("PDD never converts numeric site data to an address", "—".equals(
                AppParcelData.pinduoduo(new JSONObject().put("result", pickupSite).toString()).get(0).place));
        System.out.println("AppParcelDataTest TOTAL: PASS=" + passed + " FAIL=0");
    }
}
