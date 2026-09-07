import java.util.regex.*;

public class Feat {
    public static void main(String[] a) {
        String body = "【某App】动态验证码 33-3-0444 请在5分钟内输入（验证码类，无快递特征词）";
        String[] words = {"驿站","快递柜","丰巢","菜鸟","包裹","取件","取货","自提","到站","已到","送达","兔喜","妈妈驿站","中邮","熊猫快收","收发室","快递","代收"};
        for (String w : words) {
            if (body.contains(w)) System.out.println("CONTAINS: " + w);
        }
        Pattern p = Pattern.compile("驿站|快递柜|丰巢|菜鸟|包裹|取件|取货|自提|到站|已到|送达|兔喜|妈妈驿站|中邮|熊猫快收|收发室|快递|代收");
        Matcher m = p.matcher(body);
        if (m.find()) System.out.println("REGEX MATCH: [" + m.group() + "] at " + m.start());
        else System.out.println("REGEX: no match");
    }
}
