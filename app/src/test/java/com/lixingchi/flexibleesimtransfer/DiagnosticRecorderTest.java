package com.lixingchi.flexibleesimtransfer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class DiagnosticRecorderTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String HEADER = "{\"event\":\"capture_start\"}";
    private static final int[] UIDS = {10001, 10002, 10003};

    private static String read(java.nio.file.Path file) throws java.io.IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    static class FakeProcess extends Process {
        InputStream input;
        OutputStream output = new ByteArrayOutputStream();
        boolean alive;
        FakeProcess(String lines) { input = new ByteArrayInputStream(lines.getBytes(StandardCharsets.UTF_8)); }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return !alive; }
        @Override public int exitValue() { return 0; }
        @Override public boolean isAlive() { return alive; }
        @Override public void destroy() { alive = false; try { output.close(); } catch (Exception ignored) { } }
        @Override public Process destroyForcibly() { destroy(); return this; }
    }

    @Test public void recordsAreDurableRedactedAndUnexpectedExitIsNotSuccess() throws Exception {
        var file = temporary.getRoot().toPath().resolve("report.jsonl");
        AtomicInteger launches = new AtomicInteger(), ready = new AtomicInteger();
        DiagnosticRecorder capture = new DiagnosticRecorder(command -> {
            launches.incrementAndGet();
            return new FakeProcess("FET_READER_STARTED\nE/EuiccGoogle(1): Mapping for error code: 525060\n"
                    + "I/NearbyConnections(2): [DctDeviceAttestationManager] Device integrity token: SECRET\n");
        });
        assertEquals(DiagnosticRecorder.End.READER_UNAVAILABLE, capture.run(file, HEADER, 5, UIDS, ready::incrementAndGet));
        String saved = read(file);
        assertFalse(saved.contains("SECRET"));
        assertTrue(saved.contains("525060"));
        assertTrue(saved.contains("\"unrecognized_related_lines\":1"));
        assertTrue(saved.contains("capture_end"));
        assertEquals(1, ready.get());
        assertEquals(1, launches.get());
    }

    @Test public void existingReportIsNotOverwrittenOrRootStarted() throws Exception {
        var file = temporary.getRoot().toPath().resolve("report.jsonl");
        Files.write(file, "existing".getBytes(StandardCharsets.UTF_8));
        DiagnosticRecorder capture = new DiagnosticRecorder(command -> { fail("Must not launch"); return null; });
        assertEquals(DiagnosticRecorder.End.STORAGE_FAILED, capture.run(file, HEADER, 5, UIDS, () -> fail("Not ready")));
        assertEquals("existing", read(file));
    }

    @Test public void rootDenialAndPreStartStopDoNotReportReadyOrRetry() throws Exception {
        AtomicInteger launches = new AtomicInteger();
        DiagnosticRecorder denied = new DiagnosticRecorder(command -> {
            launches.incrementAndGet();
            return new FakeProcess("permission denied SECRET\n");
        });
        var file = temporary.getRoot().toPath().resolve("denied.jsonl");
        assertEquals(DiagnosticRecorder.End.READER_UNAVAILABLE, denied.run(file, HEADER, 5, UIDS, () -> fail("Not ready")));
        assertFalse(read(file).contains("SECRET"));
        assertEquals(1, launches.get());
        DiagnosticRecorder stopped = new DiagnosticRecorder(command -> { fail("Must not launch"); return null; });
        stopped.stop(DiagnosticRecorder.End.STOPPED);
        assertEquals(DiagnosticRecorder.End.STOPPED, stopped.run(temporary.getRoot().toPath().resolve("stopped.jsonl"),
                HEADER, 5, UIDS, () -> fail("Not ready")));
    }

    @Test public void durationClosesStdinAndKeepsTheFooter() throws Exception {
        FakeProcess child = new FakeProcess("");
        PipedInputStream input = new PipedInputStream();
        PipedOutputStream feed = new PipedOutputStream(input);
        child.input = input;
        child.alive = true;
        child.output = new ByteArrayOutputStream() {
            @Override public void close() throws java.io.IOException { child.alive = false; feed.close(); }
        };
        feed.write("FET_READER_STARTED\n".getBytes(StandardCharsets.UTF_8));
        var file = temporary.getRoot().toPath().resolve("timeout.jsonl");
        long before = System.nanoTime();
        assertEquals(DiagnosticRecorder.End.DURATION_REACHED,
                new DiagnosticRecorder(command -> child).run(file, HEADER, 1, UIDS, () -> { }));
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - before) < 5);
        assertFalse(child.alive);
        assertTrue(read(file).contains("DURATION_REACHED"));
    }

    @Test public void elapsedDeadlineDiscardsEventsAfterDeviceSleep() throws Exception {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var file = temporary.getRoot().toPath().resolve("slept.jsonl");
        FakeProcess child = new FakeProcess("FET_READER_STARTED\nE/EuiccGoogle(1): Mapping for error code: 525060\n");
        DiagnosticRecorder recorder = new DiagnosticRecorder(command -> child, clock::get);
        assertEquals(DiagnosticRecorder.End.DURATION_REACHED,
                recorder.run(file, HEADER, 5, UIDS, () -> clock.set(10000)));
        assertFalse(read(file).contains("525060"));
        assertTrue(read(file).contains("DURATION_REACHED"));
    }

    @Test public void capacityStopsCaptureWithoutDiscardingEarlierEvents() throws Exception {
        var file = temporary.getRoot().toPath().resolve("full.jsonl");
        String line = "I/FlexibleEsimTransfer(1): hooks: READY:LPA\n";
        FakeProcess child = new FakeProcess("FET_READER_STARTED\n" + line.repeat(20000));
        assertEquals(DiagnosticRecorder.End.LIMIT_REACHED,
                new DiagnosticRecorder(command -> child).run(file, HEADER, 30, UIDS, () -> { }));
        assertTrue(Files.size(file) <= DiagnosticJournal.MAX_BYTES);
        String saved = read(file);
        assertTrue(saved.startsWith("{\"elapsed_ms\":0,\"event\":\"capture_start\"}"));
        assertTrue(saved.contains("LIMIT_REACHED"));
    }

    @Test public void commandIsReadOnlyBoundedAndOwnerScoped() throws Exception {
        String command = DiagnosticRecorder.command(60, UIDS);
        assertTrue(command.startsWith("/system/bin/toybox timeout -s TERM 65 "));
        assertTrue(command.endsWith("recorder 10001,10002,10003"));
        for (String forbidden : new String[] {"setprop", "logcat -c", "am start", "force-stop", "pm clear", "killall"})
            assertFalse(command.contains(forbidden));
        assertThrows(IllegalArgumentException.class, () -> DiagnosticRecorder.command(0, UIDS));
        assertThrows(IllegalArgumentException.class, () -> DiagnosticRecorder.command(3601, UIDS));
        assertThrows(IllegalArgumentException.class, () -> DiagnosticRecorder.command(30, new int[] {1010001}));
        assertThrows(IllegalArgumentException.class, () -> DiagnosticRecorder.command(30, new int[] {}));
    }
}
