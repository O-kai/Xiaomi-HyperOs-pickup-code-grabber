package io.github.okaidev.pickupcode;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Bounded one-shot command execution; both pipes are drained while the child runs. */
final class ProcessOutput {
    private static final int MAX_CAPTURE = 1024 * 1024;

    static final class Result {
        final int code;
        final String out;
        final String err;
        final boolean timedOut;

        Result(int code, String out, String err, boolean timedOut) {
            this.code = code;
            this.out = out;
            this.err = err;
            this.timedOut = timedOut;
        }
    }

    private static final class Drain extends Thread {
        private final InputStream input;
        private final StringBuilder captured = new StringBuilder();
        volatile boolean failed;

        Drain(InputStream input) {
            super("pickup-command-output");
            this.input = input;
            setDaemon(true);
        }

        @Override public void run() {
            try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                char[] buffer = new char[4096];
                int n;
                while ((n = reader.read(buffer)) != -1) {
                    synchronized (captured) {
                        int keep = Math.min(n, MAX_CAPTURE - captured.length());
                        if (keep > 0) captured.append(buffer, 0, keep);
                        // Continue draining after the capture limit to avoid a full pipe.
                    }
                }
            } catch (Exception e) {
                failed = true;
            }
        }

        String output() {
            synchronized (captured) { return captured.toString(); }
        }
    }

    static Result collect(Process process, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        Drain out = new Drain(process.getInputStream());
        Drain err = new Drain(process.getErrorStream());
        out.start();
        err.start();
        boolean finished = false;
        try {
            // SQL is supplied by file redirection; no command here consumes Java stdin.
            try { process.getOutputStream().close(); } catch (Exception ignored) { }
            finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (finished) {
                joinUntil(out, deadline);
                joinUntil(err, deadline);
            }
            boolean timeout = !finished || out.isAlive() || err.isAlive();
            int code = !timeout && !out.failed && !err.failed ? process.exitValue() : -1;
            return new Result(code, out.output(), err.output(), timeout);
        } finally {
            if (!finished || out.isAlive() || err.isAlive()) process.destroyForcibly();
            Runnable closePipes = () -> {
                try { process.getInputStream().close(); } catch (Exception ignored) { }
                try { process.getErrorStream().close(); } catch (Exception ignored) { }
            };
            if (out.isAlive() || err.isAlive()) {
                // A descendant can keep a pipe open after su exits. Some stream close()
                // implementations wait for a blocked reader: never do that on the caller.
                Thread cleanup = new Thread(closePipes, "pickup-command-cleanup");
                cleanup.setDaemon(true);
                cleanup.start();
            } else {
                closePipes.run();
            }
        }
    }

    private static void joinUntil(Thread thread, long deadline) throws InterruptedException {
        long remaining = deadline - System.nanoTime();
        if (remaining > 0) thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
    }

    private ProcessOutput() { }
}
