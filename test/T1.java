import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class T1 {
    public static void main(String[] a) {
        String body = "【驿小哥】您的快递圆通，已到江南绿城西门北50米顺丰速运驿收发，凭4-3-5120免费取，如有疑问可到店咨询，谢谢。";
        String dash = "(?:[A-Za-z]-\\d{1,6}|[A-Za-z]?\\d{1,6})(?:-[A-Za-z]?\\d{1,6}){1,3}";
        String alnum = "[A-Za-z]?\\d{3,9}";
        String look = "到至去取领取票";
        String pBy = "(?:凭|出示)[\\s\"'“”「」《》【】\\[\\]]*(" + dash + "|" + alnum
                + ")(?=[\\s\"'“”「」《》【】\\[\\]，。,]|[" + look + "]|$)";
        System.out.println("pattern = " + pBy);
        Matcher m = Pattern.compile(pBy).matcher(body);
        boolean found = m.find();
        System.out.println("match = " + found);
        if (found) System.out.println("group1 = [" + m.group(1) + "]");

        // 单独看 dash 是否匹配 4-3-5120
        System.out.println("dash matches 4-3-5120 = " + Pattern.compile(dash).matcher("4-3-5120").matches());

        // 看 lookahead 是否成立：码后面是「免费取」
        int idx = body.indexOf("4-3-5120");
        String after = body.substring(idx + "4-3-5120".length(), idx + "4-3-5120".length() + 3);
        System.out.println("code followed by = [" + after + "]");
        System.out.println("first char is 免 -> in lookahead? " + look.contains(String.valueOf(after.charAt(0))));
    }
}
