package io.github.okaidev.pickupcode;

/**
 * 运行期文件位置的唯一事实源（v4.0.2 第八轮）。
 *
 * <p><b>为什么要有这个类</b>：sqlite3 与依赖库原先部署在
 * {@code /data/local/tmp/pickup_sqlite} —— 那是【应用数据目录之外】的位置，
 * 卸载 App 时系统不会清理，于是手机上会永久残留 1.5MB 的二进制文件和一堆临时文件。
 * 用户明确要求：「卸载程序时，能把程序连带的所有资源或相关内容全部卸载或删除掉」。
 *
 * <p>现在改部署到 {@code /data/data/<包名>/files/rt}：
 * 它属于应用私有数据，<b>卸载时由系统自动整体删除</b>，不需要任何额外清理动作。
 * 真机已验证 root（{@code su -M}）可以直接执行这里的二进制：
 *
 * <pre>
 *   LD_LIBRARY_PATH=/data/data/&lt;pkg&gt;/files/rt/lib \
 *     /data/data/&lt;pkg&gt;/files/rt/sqlite3 --version
 *   → 3.53.4 ... (64-bit)
 * </pre>
 *
 * <p><b>为什么用固定绝对路径而不是 Context</b>：本模块的代码会被注入到
 * {@code system_server}、{@code com.android.providers.telephony} 等【别的进程】里运行，
 * 那些进程里没有本 App 的 Context，也拿不到 getFilesDir()。
 * 而路径只是个字符串，所有进程用同一个常量即可保持一致。
 * （代价：暂不支持多用户分身场景，但本模块是整机级工具，实际不会装到副用户。）
 *
 * <p><b>与同步文件的分工</b>：跨进程同步用的 app_state.json / 开关标志必须放在
 * 系统进程【无需 root 就能读】的地方，所以仍在 {@link #LEGACY_DIR}。
 * 那里只剩几个几 KB 的文本文件，二进制已经搬走。
 */
public final class RuntimePaths {

    private RuntimePaths() { }

    /** 本模块包名（与 AndroidManifest 一致） */
    public static final String PKG = "io.github.okaidev.pickupcode";

    /**
     * 旧版部署目录：在应用数据目录之外，卸载不会清理。
     * 保留这个常量只为了「迁移 / 清理旧残留」，新代码不要再往里写二进制。
     */
    public static final String LEGACY_DIR = "/data/local/tmp/pickup_sqlite";

    /** 新版部署目录：位于应用私有数据内，卸载时由系统自动删除 */
    public static final String RT_DIR = "/data/data/" + PKG + "/files/rt";

    /** 新版部署目录（别名，语义更直白） */
    public static final String DIR = RT_DIR;

    /** 依赖库目录 */
    public static final String LIBS = RT_DIR + "/lib";

    /** sqlite3 可执行文件 */
    public static final String SQLITE = RT_DIR + "/sqlite3";

    /** 跨进程同步目录（系统进程免 root 可读；卸载后可能残留几个 KB 文本） */
    public static final String SYNC_DIR = LEGACY_DIR;

    /** 跨进程同步快照 */
    public static final String SYNC_FILE = SYNC_DIR + "/app_state.json";

    /** 通知通道开关标志（system_server 里的 NotiHook 要读，故与快照同目录） */
    public static final String FLAG_FILE = SYNC_DIR + "/enable_noti_hook";

    /** 用户规则（系统进程要读） */
    public static final String USER_RULES_FILE = SYNC_DIR + "/user_rules.json";

    /**
     * 需要「让 system_server 读得到」的全部配置文件。
     *
     * <p>App 用 root 写进 {@link #SYNC_DIR} 的文件，SELinux 标签默认是
     * {@code app_data_file} 或 {@code shell_data_file}，其中
     * {@code app_data_file} 在多数 ROM 上 system_server <b>读不了</b>。
     * 所以写完要统一 chmod + chcon 成 {@code system_data_file}。
     * 这里集中列一份，避免漏掉某个文件（曾经漏过开关文件，
     * 表现为「开关打开但通知通道就是不工作」）。
     */
    public static final String[] CONFIG_FILES = {
            SYNC_FILE, FLAG_FILE, USER_RULES_FILE
    };

    /** 把一段命令里用到的库路径前缀（避免每处都手写 LD_LIBRARY_PATH） */
    public static String ld() {
        return "LD_LIBRARY_PATH=" + LIBS + " ";
    }
}
