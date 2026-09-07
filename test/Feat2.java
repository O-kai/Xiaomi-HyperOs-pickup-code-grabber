import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.*;

public class Feat2 {
    public static void main(String[] a) throws Exception {
        String body = "【某App】动态验证码 33-3-0444 请在5分钟内输入（验证码类，无快递特征词）";
        Pattern p = Pattern.compile("驿站|快递柜|丰巢|菜鸟|包裹|取件|取货|自提|到站|已到|送达|兔喜|妈妈驿站|中邮|熊猫快收|收发室|快递|代收");
        Matcher m = p.matcher(body);
        StringBuilder sb = new StringBuilder();
        while (m.find()) sb.append("MATCH [").append(m.group()).append("] at ").append(m.start()).append("\n");
        if (sb.length() == 0) sb.append("NO MATCH\n");
        sb.append("body chars: ");
        for (int i = 0; i < body.length(); i++) sb.append(i).append(":").append(body.charAt(i)).append(" ");
        Writer w = new OutputStreamWriter(new FileOutputStream("feat2-out.txt"), StandardCharsets.UTF_8);
        w.write(sb.toString());
        w.close();
        System.out.println("written");
    }
}
