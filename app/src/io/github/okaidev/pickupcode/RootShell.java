package io.github.okaidev.pickupcode;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 常驻 root 通道（v4.0.3）。
 *
 * <p><b>要解决的问题</b>：Magisk 的 {@code su} 每被调用一次，就会弹一次
 * 「取件码助手已被授予超级用户权限」。本模块抓到一个取件码就要写一次小米笔记待办
 * （{@link TodoWriter#handle} → {@code runSqlForOutput} → {@code su}），
 * 于是用户看到的就是「时不时弹一次」——因为取件短信本身就不常有。
 *
 * <p><b>怎么解决</b>：{@code su -c sh} 起一个常驻的 root shell，
 * 之后所有 SQL 都通过它的 stdin/stdout 走，不再重复调 su。
 * 于是 su 只在建立 shell 时被调用一次（弹一次），
 * 后续写入全程静默。
 *
 * <p><b>生命周期</b>：shell 由 App 进程持有。App 进程被杀 → shell 的 stdin 断开 →
 * 非交互 sh 读到 EOF 自行退出，不留残余进程。下次需要时重建（会再弹一次）。
 *
 * <p><b>协议</b>：
 * <ol>
 *   <li>建立后先发 {@code echo "__PC_READY__"}，读到此标记前的内容全部丢弃
 *       ——那是 Magisk 的横幅（"Magisk v27.0 ..."），不能混进业务输出。</li>
 *   <li>每条命令后跟 {@code echo "__PC_END_<id>__:$?"}，
 *       读到该标记即表示命令结束，标记后是退出码。</li>
 * </ol>
 *
 * <p><b>失败即回退</b>：任何异常（shell 建不起来、读超时、管道断）都返回 null，
 * 调用方退回原来的一次性 su 逻辑。功能可用性优先于「不弹框」。
 */
public final class RootShell {

    private static final String TAG = "PICKUPDEBUG";

    /**
     * HyperOS 上 Magisk 的 su 在 /product/bin/su（应用 PATH 不含该目录）→ 多路径尝试
     *
     * <p><b>为什么必须带 {@code -M}</b>：{@code su -M} 会把 root 进程挂到 master mount
     * namespace，才能看到别的 App 在 {@code /data/user/0/...} 下的数据。
     * 少了它，sqlite3 打不开小米笔记的库（exit=1，unable to open database file），
     * 而一次性 su 的老路径一直带着 -M，所以两者行为必须一致。
     */
    private static final String[] SU_BINS = {
            "su", "/product/bin/su", "/system/bin/su", "/sbin/su", "/su/bin/su"
    };

    private static final long READ_POLL_MS = 30;
    private static final long DEFAULT_TIMEOUT_MS = 20_000;

    private static final Object LOCK = new Object();

    private static Process proc;
    private static BufferedWriter writer;
    private static BufferedReader reader;
    /** reader 线程把读到的行塞进这里，主线程按标记取 */
    private static BlockingQueue<String> lines;
    private static Thread readerThread;
    private static int seq = 0;

    private RootShell() {
    }

    /** shell 是否已就绪 */
    public static boolean isReady() {
        synchronized (LOCK) {
            return proc != null && proc.isAlive() && writer != null;
        }
    }

    /**
     * 预热：后台建好 root shell。
     *
     * <p>在 App 启动时调一次——此刻用户正看着屏幕，Magisk 弹一次授权是可预期的；
     * 而如果等到第一条取件短信才建，用户只会莫名其妙地收到一个弹框。
     */
    public static void warmUp(final Context ctx) {
        if (isReady()) return;
        Thread t = new Thread(() -> {
            try {
                long start = System.currentTimeMillis();
                boolean ok = ensureShell();
                Log.i(TAG, "RootShell warmUp: " + (ok ? "ok" : "failed")
                        + " (" + (System.currentTimeMillis() - start) + "ms)");
            } catch (Throwable t2) {
                Log.w(TAG, "RootShell warmUp failed: " + t2);
            }
        }, "pickup-root-warmup");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 执行结果：退出码 + 标准输出。
     */
    public static final class Result {
        public final int code;
        public final String output;

        Result(int code, String output) {
            this.code = code;
            this.output = output;
        }

        public boolean ok() {
            return code == 0;
        }
    }

    /**
     * 在常驻 shell 里执行一段脚本。
     *
     * @return 执行结果；不可用或出错时返回 null（调用方应回退到一次性 su）
     */
    public static Result execResult(String script, long timeoutMs) {
        synchronized (LOCK) {
            try {
                if (!ensureShell()) return null;
                int id = ++seq;
                String marker = "__PC_END_" + id + "__";
                writer.write(script);
                writer.write("\n");
                writer.write("echo \"" + marker + ":$?\"\n");
                writer.flush();

                StringBuilder sb = new StringBuilder();
                long deadline = System.currentTimeMillis() + timeoutMs;
                while (true) {
                    long remain = deadline - System.currentTimeMillis();
                    if (remain <= 0) {
                        Log.w(TAG, "RootShell exec timeout id=" + id);
                        reset();
                        return null;
                    }
                    String line = lines.poll(remain, TimeUnit.MILLISECONDS);
                    if (line == null) {
                        Log.w(TAG, "RootShell exec read timeout id=" + id);
                        reset();
                        return null;
                    }
                    if (line.startsWith(marker)) {
                        int code = 0;
                        try {
                            code = Integer.parseInt(
                                    line.substring(marker.length() + 1).trim());
                        } catch (Throwable ignored) { }
                        return new Result(code, sb.toString());
                    }
                    sb.append(line).append('\n');
                }
            } catch (Throwable t) {
                Log.w(TAG, "RootShell exec failed: " + t);
                reset();
                return null;
            }
        }
    }

    /**
     * 在常驻 shell 里执行一段脚本，返回其标准输出。
     *
     * @return 输出文本；不可用或出错时返回 null（调用方应回退到一次性 su）
     */
    public static String exec(String script, long timeoutMs) {
        Result r = execResult(script, timeoutMs);
        return r == null ? null : r.output;
    }

    public static String exec(String script) {
        return exec(script, DEFAULT_TIMEOUT_MS);
    }

    // ================================================================
    //  内部
    // ================================================================

    /** 建立（或复用）常驻 shell。必须在 LOCK 内调用。 */
    private static boolean ensureShell() throws Exception {
        if (proc != null && proc.isAlive() && writer != null && reader != null) {
            return true;
        }
        reset();

        Process last = null;
        Exception lastErr = null;
        for (String suBin : SU_BINS) {
            try {
                Process p = Runtime.getRuntime().exec(
                        new String[]{"sh", "-c", suBin + " -M -c sh"});
                last = p;

                // 管道的方向：
                //   p.getOutputStream() → su 的 stdin → sh 的 stdin   （写命令）
                //   p.getInputStream()  ← su 的 stdout ← sh 的 stdout （读输出）
                writer = new BufferedWriter(new OutputStreamWriter(
                        p.getOutputStream(), StandardCharsets.UTF_8));
                reader = new BufferedReader(new InputStreamReader(
                        p.getInputStream(), StandardCharsets.UTF_8));

                // stderr 单独 drain 掉：Magisk 的横幅与错误信息走这里，
                // 不能混进 stdout（会污染业务输出），也不能不读（管道写满会阻塞 su）。
                final Process fp = p;
                Thread errDrain = new Thread(() -> {
                    try {
                        java.io.BufferedReader er = new BufferedReader(new InputStreamReader(
                                fp.getErrorStream(), StandardCharsets.UTF_8));
                        String el;
                        while ((el = er.readLine()) != null) {
                            Log.i(TAG, "RootShell stderr: " + el);
                        }
                    } catch (Throwable ignored) { }
                }, "pickup-root-err");
                errDrain.setDaemon(true);
                errDrain.start();

                proc = p;
                lines = new ArrayBlockingQueue<>(256);
                readerThread = new Thread(() -> {
                    try {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (!lines.offer(line)) lines.poll();
                        }
                    } catch (Throwable ignored) { }
                }, "pickup-root-reader");
                readerThread.setDaemon(true);
                readerThread.start();

                // 吃掉 Magisk 横幅：读到 __PC_READY__ 之前的都丢弃
                writer.write("echo \"__PC_READY__\"\n");
                writer.flush();
                long deadline = System.currentTimeMillis() + DEFAULT_TIMEOUT_MS;
                boolean ready = false;
                while (System.currentTimeMillis() < deadline) {
                    String line = lines.poll(200, TimeUnit.MILLISECONDS);
                    if (line == null) continue;
                    if (line.contains("__PC_READY__")) { ready = true; break; }
                }
                if (!ready) {
                    Log.w(TAG, "RootShell: shell not ready for " + suBin);
                    reset();
                    continue;
                }
                Log.i(TAG, "RootShell ready via " + suBin);
                return true;
            } catch (Throwable t) {
                lastErr = t instanceof Exception ? (Exception) t : new RuntimeException(t);
                Log.w(TAG, "RootShell: " + suBin + " failed: " + t);
                reset();
            }
        }
        if (lastErr != null) Log.w(TAG, "RootShell: all su paths failed: " + lastErr);
        return false;
    }

    /** 拆掉当前 shell。必须在 LOCK 内调用。 */
    private static void reset() {
        try {
            if (proc != null) proc.destroyForcibly();
        } catch (Throwable ignored) { }
        try {
            if (readerThread != null) readerThread.interrupt();
        } catch (Throwable ignored) { }
        proc = null;
        writer = null;
        reader = null;
        readerThread = null;
        lines = null;
    }
}
