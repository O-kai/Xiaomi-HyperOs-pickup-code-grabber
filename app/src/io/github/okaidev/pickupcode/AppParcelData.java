package io.github.okaidev.pickupcode;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Schemas verified against Cainiao 8.11.1105 and Pinduoduo 8.31.0 APKs. */
public final class AppParcelData {
    public static final String CAINIAO = "com.cainiao.wireless";
    public static final String PINDUODUO = "com.xunmeng.pinduoduo";
    public static final String PDD_TRACKBOX = "/api/express_trackbox/list/cabinet/package";
    public static final String PDD_LIST = "/api/express_trackbox/list";
    public static final int MAX_PAYLOAD = 256 * 1024;
    public static final int MAX_EVENTS = 100;

    private AppParcelData() { }

    public static boolean supports(String pkg) {
        return CAINIAO.equals(pkg) || PINDUODUO.equals(pkg);
    }

    public static String label(String pkg) {
        return CAINIAO.equals(pkg) ? "菜鸟" : PINDUODUO.equals(pkg) ? "拼多多" : "—";
    }

    public static final class Event {
        public final String code;
        public final String place;

        public Event(String code, String place) {
            this.code = code;
            this.place = cleanPlace(place);
        }
    }

    public static boolean validCode(String code) {
        return code != null && code.length() <= 20
                && code.matches("[A-Za-z0-9]+(?:-[A-Za-z0-9]+){0,4}")
                && code.matches(".*[0-9].*")
                && !code.matches("[0-9]{9,}");
    }

    public static String cleanPlace(String place) {
        if (place == null) return "—";
        String value = place.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (value.isEmpty()) return "—";
        return value.length() > 200 ? value.substring(0, 200) : value;
    }

    /** The caller passes only data from MtopResponse, never request headers/tokens. */
    public static List<Event> cainiaoResponse(String json) {
        List<Event> out = new ArrayList<>();
        if (!bounded(json)) return out;
        try {
            visit(new JSONObject(json), 0, new int[]{5000}, out);
        } catch (Exception ignored) { }
        return out;
    }

    private static void visit(Object value, int depth, int[] budget, List<Event> out) {
        if (depth > 16 || --budget[0] < 0 || out.size() >= MAX_EVENTS) return;
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length() && budget[0] > 0 && out.size() < MAX_EVENTS; i++) {
                visit(array.opt(i), depth + 1, budget, out);
            }
        } else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONObject station = object.optJSONObject("packageStation");
            // SIGN is recipient delivery; AGENT_SIGN is arrival at a collection point.
            if (station != null && "AGENT_SIGN".equals(object.optString("logisticsStatus"))) {
                String code = station.optString("authCode", "").trim();
                if (station.optBoolean("showAuthCode", true) && validCode(code)) {
                    out.add(new Event(code, station.optString("stationName", "")));
                }
            }
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext() && budget[0] > 0 && out.size() < MAX_EVENTS) {
                visit(object.opt(keys.next()), depth + 1, budget, out);
            }
        }
    }

    /** Legacy pickup dataset delivered by the JS data layer, before native rendering. */
    public static List<Event> cainiaoPickup(String json) {
        List<Event> out = new ArrayList<>();
        if (!bounded(json)) return out;
        try {
            JSONObject root = new JSONObject(json);
            if (root.optInt("pageState", -1) != 2) return out;
            JSONArray spots = root.optJSONArray("spotsViewModels");
            if (spots == null) return out;
            for (int i = 0; i < spots.length() && i < MAX_EVENTS; i++) {
                JSONObject spot = spots.optJSONObject(i);
                if (spot == null) continue;
                JSONArray packages = spot.optJSONArray("packageItems");
                if (packages == null) continue;
                String place = spot.optString("title", "");
                String address = spot.optString("address", "");
                if (!address.isEmpty()) place = place.isEmpty() ? address : place + " · " + address;
                for (int j = 0; j < packages.length() && out.size() < MAX_EVENTS; j++) {
                    JSONObject item = packages.optJSONObject(j);
                    if (item == null || item.optInt("isSigning", 0) != 0) continue;
                    String code = item.optString("title", "").trim();
                    if (validCode(code)) out.add(new Event(code, place));
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Homepage JS dataset, observed before native rendering; preserve each station block within its group. */
    public static List<Event> cainiaoHomepage(String json) {
        List<Event> out = new ArrayList<>();
        if (!bounded(json)) return out;
        try {
            JSONObject data = new JSONObject(json).optJSONObject("data");
            JSONArray items = data == null ? null : data.optJSONArray("packageDataItemArray");
            if (items == null || items.length() > 500) return out;
            String stationGroup = "";
            String stationAnchor = "";
            String stationPlace = null;
            for (int i = 0; i < items.length() && out.size() < MAX_EVENTS; i++) {
                JSONObject item = items.optJSONObject(i);
                JSONObject card = homepageCard(item);
                if (item == null) continue;
                String kind = item.optString("bizzType");
                // Multiple stations can share one group ID; preserve the producer's
                // contiguous station blocks rather than letting the last header win.
                if ("STATION_INFO".equals(kind) || item.optBoolean("isGroupHeader", false)
                        || kind.startsWith("PKG_GROUP_") || "PKG_ANCHOR".equals(kind)) {
                    stationGroup = "";
                    stationAnchor = "";
                    stationPlace = null;
                }
                JSONObject fields = card == null ? null : card.optJSONObject("data");
                if (fields == null || !kind.equals(card.optString("tag"))) continue;
                if ("STATION_INFO".equals(kind)) {
                    stationGroup = item.optString("groupId");
                    stationAnchor = item.optString("anchorId");
                    stationPlace = fields.optString("stationTitle", "");
                    String subtitle = fields.optString("stationSubtitle", "");
                    if (!subtitle.isEmpty()) stationPlace = stationPlace.isEmpty() ? subtitle : stationPlace + " · " + subtitle;
                    continue;
                }
                if (!"PKG_INFO_ITEM".equals(kind)
                        || !"AGENT_SIGN".equals(fields.optString("logisticStatus"))) continue;
                String code = fields.optString("title", "").trim();
                if (!validCode(code)) {
                    JSONObject rich = fields.optJSONObject("richTitle");
                    JSONArray nodes = rich == null ? null : rich.optJSONArray("attributedNodes");
                    StringBuilder text = new StringBuilder();
                    if (nodes != null && nodes.length() <= 20) {
                        for (int j = 0; j < nodes.length(); j++) {
                            JSONObject node = nodes.optJSONObject(j);
                            if (node != null) text.append(node.optString("text", ""));
                        }
                    }
                    code = text.toString().trim();
                }
                boolean sameStationBlock = !stationGroup.isEmpty()
                        && stationGroup.equals(item.optString("groupId"))
                        && stationAnchor.equals(item.optString("anchorId"));
                if (validCode(code)) out.add(new Event(code, sameStationBlock ? stationPlace : null));
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static JSONObject homepageCard(JSONObject item) {
        if (item == null) return null;
        Object value = item.opt("packageData");
        if (value instanceof JSONObject) return (JSONObject) value;
        if (value instanceof String && bounded((String) value)) {
            try { return new JSONObject((String) value); } catch (Exception ignored) { }
        }
        return null;
    }

    /** CabinetInfo.Result: only status 3 displays an available pickup code. */
    public static List<Event> pinduoduo(String json) {
        List<Event> out = new ArrayList<>();
        if (!bounded(json)) return out;
        try {
            JSONObject result = new JSONObject(json).optJSONObject("result");
            if (result == null || result.optInt("status", -1) != 3
                    || result.optBoolean("showQrCodePickUp", false)) return out;
            String code = pinduoduoCode(result.opt("code"));
            if (validCode(code)) out.add(new Event(code, pinduoduoAddress(result)));
        } catch (Exception ignored) { }
        return out;
    }

    /** Logistics assistant's pending pickup list; contains no delivered/transport items. */
    public static List<Event> pinduoduoTrackbox(String json) {
        List<Event> out = new ArrayList<>();
        if (!bounded(json)) return out;
        try {
            JSONObject root = new JSONObject(json);
            if (!root.optBoolean("success", false)) return out;
            JSONObject result = root.optJSONObject("result");
            JSONArray stations = result == null ? null : result.optJSONArray("combine_list");
            if (stations == null) return out;
            for (int i = 0; i < stations.length() && i < MAX_EVENTS && out.size() < MAX_EVENTS; i++) {
                JSONObject station = stations.optJSONObject(i);
                if (station == null) continue;
                JSONArray tracks = station.optJSONArray("combine_track_list");
                if (tracks == null) continue;
                String place = pinduoduoAddress(station);
                for (int j = 0; j < tracks.length() && j < MAX_EVENTS && out.size() < MAX_EVENTS; j++) {
                    JSONObject track = tracks.optJSONObject(j);
                    if (track == null) continue;
                    String code = pinduoduoCode(track.opt("pick_code"));
                    if (validCode(code)) out.add(new Event(code, place));
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** My Express list mixes transport, pickup and delivered parcels; 400 is awaiting pickup. */
    public static List<Event> pinduoduoList(String json) {
        List<Event> out = new ArrayList<>();
        if (!bounded(json)) return out;
        try {
            JSONObject root = new JSONObject(json);
            if (!root.optBoolean("success", false)) return out;
            JSONObject result = root.optJSONObject("result");
            JSONArray tracks = result == null ? null : result.optJSONArray("track_info_list");
            if (tracks == null) return out;
            for (int i = 0; i < tracks.length() && i < 500 && out.size() < MAX_EVENTS; i++) {
                JSONObject track = tracks.optJSONObject(i);
                if (track == null || track.optInt("status_type", -1) != 400) continue;
                String code = pinduoduoCode(track.opt("pick_code"));
                // The list has no station address. Do not guess it from a truncated tracking description.
                if (validCode(code)) out.add(new Event(code, null));
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static String pinduoduoCode(Object value) {
        // These API fields are display strings. Never turn a JSON number/object into a code,
        // or fall back to a phone suffix / tracking description when the field is absent.
        if (!(value instanceof String)) return "";
        String code = ((String) value).trim();
        if (code.startsWith("取件码")) code = code.substring(3).trim().replaceFirst("^[:：]\\s*", "");
        return code;
    }

    private static String pinduoduoAddress(JSONObject pickup) {
        Object address = pickup.opt("address");
        if (address instanceof String && !((String) address).trim().isEmpty())
            return ((String) address).trim();
        // CabinetInfo.Result.CnInOutBoundResponse is another explicit pickup-site address.
        JSONObject inOut = pickup.optJSONObject("cnInOutBound");
        Object nested = inOut == null ? null : inOut.opt("address");
        return nested instanceof String ? ((String) nested).trim() : "";
    }

    private static boolean bounded(String value) {
        return value != null && !value.isEmpty() && value.length() <= MAX_PAYLOAD;
    }
}
