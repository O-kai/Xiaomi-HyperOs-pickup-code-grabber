import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TestPlaceRefined2 {
    // 匹配 (?:已到|到达|到|在) + 地名 + (?:店|站|柜|点|自提|快递)
    // 捕获组只保留真正的地点名
    private static final Pattern P_PLACE = Pattern.compile(
            "(?:已到|到达|到|在)([\\u4e00-\\u9fa5A-Za-z0-9]{2,30}?(?:店|站|柜|点|自提|快递))");

    public static void main(String[] args) {
        String[] samples = {
            "【菜鸟驿站】测试包裹：取件码为99-9-1234，请到XX小区快递驿站取件（测试条目，可删除）",
            "【妈妈驿站】取货码5-5-9-13，您有包裹YT764306505052已到苍山下坡圆通快递",
            "【申通快递】请凭9-7-0993到苍山老菜场斜对面申通快递取您的快递，详询代收驿站",
            "【菜鸟驿站】您的包裹已到站，凭4-6-7602到新郑龙湖富田兴龙湾31号楼店取件。",
            "【兔喜生活】您有包裹已到达兴龙湾30栋兔喜店，取件码为2-3-05025，地址:兴龙湾30号楼102"
        };

        for (String s : samples) {
            Matcher m = P_PLACE.matcher(s);
            System.out.println(m.find() ? m.group(1) : "—");
        }
    }
}
