import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class TestPddTrace {
    public static void main(String[] args) throws Exception {
        Path srcDir = Paths.get("D:\\project\\Xiaomi-HyperOs-pickup-code-grabber\\app\\src\\io\\github\\okaidev\\pickupcode");
        String aj = "D:\\project\\_build-tools\\sdk\\platforms\\android-34\\android.jar";
        
        URLClassLoader cl = new URLClassLoader(new URL[]{
            srcDir.toUri().toURL(),
            Paths.get(aj).toUri().toURL()
        });
        
        // 直接测试 ExtractorRules 和 正则
        String dash = "(?:[A-Za-z]-\\d{1,6}|[A-Za-z]?\\d{1,6})(?:-[A-Za-z]?\\d{1,6}){0,3}";
        System.out.println("Pattern test on '6': " + "6".matches(dash));
    }
}
