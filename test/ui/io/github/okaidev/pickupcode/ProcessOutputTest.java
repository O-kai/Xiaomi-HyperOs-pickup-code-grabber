package io.github.okaidev.pickupcode;

import org.junit.Test;
import static org.junit.Assert.*;

/** Real pipes and processes, including enough output to fill both OS pipe buffers. */
public class ProcessOutputTest {
    private static Process shell(String script) throws Exception {
        return new ProcessBuilder("sh", "-c", script).start();
    }

    @Test public void successfulQueryReturnsStdoutAndStderr() throws Exception {
        ProcessOutput.Result result = ProcessOutput.collect(shell("printf '42\\n'; printf 'warning\\n' >&2"), 3000);
        assertFalse(result.timedOut);
        assertEquals(0, result.code);
        assertEquals("42\n", result.out);
        assertEquals("warning\n", result.err);
    }

    @Test public void largeOutputDrainsBothPipesWithoutDeadlock() throws Exception {
        ProcessOutput.Result result = ProcessOutput.collect(shell(
                "head -c 262144 /dev/zero; head -c 262144 /dev/zero >&2; printf 'PICKUP_ROW:19\\n'"), 5000);
        assertFalse(result.timedOut);
        assertEquals(0, result.code);
        assertEquals(262144, result.err.length());
        assertTrue(result.out.endsWith("PICKUP_ROW:19\n"));
    }

    @Test public void nonzeroExitRetainsDiagnosticOutput() throws Exception {
        ProcessOutput.Result result = ProcessOutput.collect(shell("printf 'bad sql' >&2; exit 7"), 3000);
        assertEquals(7, result.code);
        assertEquals("bad sql", result.err);
    }

    @Test public void hangingChildTimesOutAndIsDestroyed() throws Exception {
        Process child = shell("exec sleep 10");
        long start = System.nanoTime();
        ProcessOutput.Result result = ProcessOutput.collect(child, 150);
        assertTrue(result.timedOut);
        assertEquals(-1, result.code);
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000);
        assertTrue(child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test public void descendantHoldingPipesCannotBlockTimeoutCleanup() throws Exception {
        Process child = shell("sleep 3 & wait");
        long start = System.nanoTime();
        ProcessOutput.Result result = ProcessOutput.collect(child, 150);
        assertTrue(result.timedOut);
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000);
        assertTrue(child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS));
    }
}
