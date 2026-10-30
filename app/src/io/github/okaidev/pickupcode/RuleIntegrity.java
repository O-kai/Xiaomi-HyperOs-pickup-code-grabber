package io.github.okaidev.pickupcode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 规则集完整性校验（纯 Java，可离线单测）。
 *
 * <p>为什么需要：热更规则是从 CDN 拉的。CDN 被劫持、缓存损坏、
 * 或者中间人改了一行正则，都可能让提取引擎行为异常——
 * 最坏的情况是把错误内容当成取件码写进用户的待办。
 *
 * <p>4.0 的校验策略（按信任强度递增）：
 * <ol>
 *   <li><b>双镜像一致</b>：同一个规则文件从两个互相独立的镜像各取一份，
 *       字节级必须一致。不一致说明中间出了问题，直接拒绝应用。</li>
 *   <li><b>同版本锚定</b>：如果拉到的规则版本号和 APK 内置的版本号相同，
 *       那么它的摘要必须等于内置摘要——同一个版本号不允许换内容。</li>
 *   <li><b>规则自检</b>：版本号必须比本地新，词表不能空、正则必须能编译
 *       （由 {@link ExtractorRules} 自己负责）。</li>
 * </ol>
 *
 * <p>真正的「签名」需要 Ed25519 + 内置公钥，能防住两个镜像同时被攻破；
 * 那属于 5.0 的范围。这里先把「内容有没有被改过」这层兜住。
 */
public final class RuleIntegrity {

    /** 校验通过 */
    public static final int OK = 0;
    /** 两个镜像内容不一致 */
    public static final int MIRROR_MISMATCH = 1;
    /** 与内置同版本但摘要对不上 */
    public static final int BUNDLED_MISMATCH = 2;
    /** 两个镜像都没取到内容 */
    public static final int NO_CONTENT = 3;
    /** 只取到一个镜像，无法做一致性比对 */
    public static final int SINGLE_SOURCE = 4;

    private RuleIntegrity() {
    }

    /**
     * 校验拉到的规则内容。
     *
     * @param contentA     镜像 A 的内容（可为 null）
     * @param contentB     镜像 B 的内容（可为 null）
     * @param bundledHash  APK 内置规则文件的 SHA-256（可为 null = 未内置摘要）
     * @param fetchedVersion 拉到的规则版本号（解析失败时传 -1）
     * @param bundledVersion APK 内置规则版本号
     * @return 上面的状态码之一
     */
    public static int verify(String contentA, String contentB,
                             String bundledHash, int fetchedVersion, int bundledVersion) {
        boolean hasA = contentA != null && !contentA.trim().isEmpty();
        boolean hasB = contentB != null && !contentB.trim().isEmpty();
        if (!hasA && !hasB) return NO_CONTENT;

        // ① 双镜像一致性
        if (hasA && hasB) {
            String ha = sha256(contentA);
            String hb = sha256(contentB);
            if (!ha.equals(hb)) return MIRROR_MISMATCH;
        } else {
            // 只剩一个源：没法比对，但内容本身仍要过下面的锚定与自检
            return SINGLE_SOURCE;
        }

        // ② 同版本锚定
        if (bundledHash != null && !bundledHash.isEmpty()
                && fetchedVersion > 0 && fetchedVersion == bundledVersion) {
            String hf = sha256(hasA ? contentA : contentB);
            if (!hf.equalsIgnoreCase(bundledHash.trim())) return BUNDLED_MISMATCH;
        }
        return OK;
    }

    /** 内容是否值得应用：版本必须比本地新 */
    public static boolean isNewer(int fetchedVersion, int currentVersion) {
        return fetchedVersion > currentVersion;
    }

    /** SHA-256 十六进制小写 */
    public static String sha256(String input) {
        if (input == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 从「<hash>  rules.json」这样的摘要文件内容里取出 hash */
    public static String parseHashFile(String content) {
        if (content == null) return "";
        String line = content.trim();
        if (line.isEmpty()) return "";
        int sp = line.indexOf(' ');
        if (sp > 0) line = line.substring(0, sp);
        int tab = line.indexOf('\t');
        if (tab > 0) line = line.substring(0, tab);
        return line.trim().toLowerCase(java.util.Locale.US);
    }
}
