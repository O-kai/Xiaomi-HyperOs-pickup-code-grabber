import io.github.okaidev.pickupcode.ExtractorRules;
import io.github.okaidev.pickupcode.PickupExtractor;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 从权威语料 sms-cases.txt 导出公开语料集 sms-corpus-public.jsonl。
 *
 * <p>取件码与「是否反例」以 sms-cases.txt 里手写的期望为准（那是权威），
 * 来源与地点取自规则引擎的实际输出（公开集不作为断言依据，只作参考素材）。
 */
public class GenCorpus {

    public static void main(String[] args) throws Exception {
        String repo = args.length > 0 ? args[0] : ".";
        String cases = repo + "/test/sms-cases.txt";
        String out = repo + "/test/sms-corpus-public.jsonl";

        ExtractorRules rules = ExtractorRules.fromJson(
                new String(Files.readAllBytes(Paths.get(repo + "/rules/rules.json")),
                        StandardCharsets.UTF_8));

        List<String> lines = Files.readAllLines(Paths.get(cases), StandardCharsets.UTF_8);
        try (PrintWriter w = new PrintWriter(
                Files.newBufferedWriter(Paths.get(out), StandardCharsets.UTF_8))) {
            for (String line : lines) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String[] parts = t.split("\\s*\\|\\s*", 3);
                if (parts.length < 3) continue;
                String expect = parts[1];
                String body = parts[2];
                List<String> codes = new ArrayList<>();
                if (!"-".equals(expect)) {
                    for (String c : expect.split(";")) {
                        if (!c.trim().isEmpty()) codes.add(c.trim());
                    }
                }
                String src = bracketSource(body);
                String place = PickupExtractor.extractPlace(body);
                // 引擎用「—」表示「没识别出地点」，公开集里用 null 更清晰
                if (place != null && (place.isEmpty() || "—".equals(place))) place = null;
                StringBuilder sb = new StringBuilder();
                sb.append("{\"sms\": ").append(json(body));
                sb.append(", \"expectCodes\": ").append(jsonArray(codes));
                sb.append(", \"expectSource\": ").append(src == null || src.isEmpty() ? "null" : json(src));
                sb.append(", \"expectPlace\": ").append(place == null || place.isEmpty() ? "null" : json(place));
                sb.append(", \"isCounterExample\": ").append(codes.isEmpty() ? "true" : "false");
                sb.append('}');
                w.println(sb);
            }
        }
        System.out.println("written: " + out);
    }

    /** 来源按【驿站名】取（与 App 的来源识别同源格式） */
    static String bracketSource(String body) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("【([^】]{1,20})】").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    static String jsonArray(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(json(list.get(i)));
        }
        return sb.append(']').toString();
    }
}
