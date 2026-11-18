package io.github.okaidev.pickupcode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * App 包名 → 简短品牌名（v13 从 {@code NotiHook} 里抽出来）。
 *
 * <p>原先这张表写在 {@code NotiHook} 里，而 {@code NotiHook} 依赖 Xposed API
 * （compileOnly），导致任何想验证「通知来源」的逻辑都没法在 JVM 上离线跑——
 * 测试链只能塞一个空 map 的替身，测了个寂寞。
 *
 * <p>这个类零 Android / Xposed 依赖，SystemServer 侧（NotiHook）与 App 侧
 * （TodoWriter.resolveSource、设置页）共用同一份事实源，
 * 离线单测也能直接引用它。
 */
public final class AppLabels {

    public static final Map<String, String> PKG_LABELS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("com.cainiao.wireless", "菜鸟");
        m.put("com.cainiao.guoguo", "菜鸟裹裹");
        m.put("com.jingdong.app.mall", "京东");
        m.put("com.taobao.taobao", "淘宝");
        m.put("com.xunmeng.pinduoduo", "拼多多");
        m.put("com.sf.cdsibm", "顺丰速运");
        m.put("com.fcbox.hiveconsumer", "丰巢");
        m.put("com.xiaomi.mi_connect_service", "设备互联");
        m.put("com.ss.android.ugc.aweme", "抖音");
        PKG_LABELS = Collections.unmodifiableMap(m);
    }

    /**
     * 通知来源：有品牌标签就用标签，否则退到包名末段；统一带「通知」后缀。
     * 「通知:」前缀由调用方去掉后再传进来。
     */
    public static String notiSource(String pkg) {
        if (pkg == null) return "—";
        String label = PKG_LABELS.get(pkg);
        if (label == null || label.isEmpty()) {
            int dot = pkg.lastIndexOf('.');
            label = dot >= 0 ? pkg.substring(dot + 1) : pkg;
        }
        return label.isEmpty() ? "—" : label + "通知";
    }

    private AppLabels() { }
}
