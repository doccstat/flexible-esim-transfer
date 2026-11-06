package com.lixingchi.flexibleesimtransfer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

final class DiagnosticRecorder {
    enum End { STOPPED, DURATION_REACHED, LIMIT_REACHED, READER_UNAVAILABLE, READ_FAILED, STORAGE_FAILED }
    interface Launcher { Process start(String command) throws IOException; }
    private final AtomicReference<End> stop = new AtomicReference<>();
    private final Launcher launcher;
    private final LongSupplier clock;
    private volatile Process process;
    private volatile boolean ready;
    private volatile ScheduledExecutorService timer;

    DiagnosticRecorder(LongSupplier clock) {
        this(command -> new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start(), clock);
    }

    DiagnosticRecorder(Launcher launcher) { this(launcher, () -> System.nanoTime() / 1_000_000); }

    DiagnosticRecorder(Launcher launcher, LongSupplier clock) { this.launcher = launcher; this.clock = clock; }

    static boolean validDuration(int seconds) { return seconds >= 1 && seconds <= 3600; }

    static String command(int seconds, int[] uids) throws IOException {
        if (!validDuration(seconds) || uids.length == 0) throw new IllegalArgumentException("Invalid capture scope");
        StringBuilder owners = new StringBuilder();
        for (int uid : uids) {
            if (uid < 1000 || uid >= 100000) throw new IllegalArgumentException("Owner-user apps only");
            if (owners.length() > 0) owners.append(',');
            owners.append(uid);
        }
        var input = DiagnosticRecorder.class.getResourceAsStream("/diagnostic-reader.sh");
        if (input == null) throw new IOException("Reader unavailable");
        String script;
        try (input) { script = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
        return "/system/bin/toybox timeout -s TERM " + (seconds + 5)
                + " /system/bin/sh -c '" + script.replace("'", "'\"'\"'") + "' recorder " + owners;
    }

    End run(Path file, String header, int seconds, int[] uids, Runnable onReady) {
        if (!validDuration(seconds)) throw new IllegalArgumentException("Invalid duration");
        long started = clock.getAsLong();
        long omitted = 0;
        End result = End.READER_UNAVAILABLE;
        timer = Executors.newSingleThreadScheduledExecutor();
        try (DiagnosticJournal journal = new DiagnosticJournal(file)) {
            if (!journal.append(header, 0)) return End.STORAGE_FAILED;
            timer.schedule(() -> stop(End.DURATION_REACHED), seconds, TimeUnit.SECONDS);
            timer.schedule(() -> { if (!ready) stop(End.READER_UNAVAILABLE); }, 30, TimeUnit.SECONDS);
            try {
                DiagnosticEvents events = new DiagnosticEvents();
                if (stop.get() == null) process = launcher.start(command(seconds, uids));
                if (process != null) {
                    if (stop.get() != null) signalStop();
                    try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                        for (String line; (line = DiagnosticEvents.readLine(reader)) != null;) {
                            if (stop.get() != null) break;
                            // Android supplies elapsedRealtime so a sleeping phone cannot extend collection.
                            if (elapsed(started) >= seconds * 1000L) { stop(End.DURATION_REACHED); break; }
                            if (!ready && line.equals("FET_READER_STARTED")) {
                                ready = true;
                                journal.append("{\"event\":\"reader_started\"}", elapsed(started));
                                onReady.run();
                                continue;
                            }
                            String record = events.parse(line);
                            if (record != null && !journal.append(record, elapsed(started))) {
                                stop(End.LIMIT_REACHED);
                                break;
                            }
                            if (record == null && DiagnosticEvents.related(line)) omitted++;
                        }
                    }
                }
                result = stop.get() != null ? stop.get() : End.READER_UNAVAILABLE;
            } catch (IOException | RuntimeException unavailable) {
                result = stop.get() != null ? stop.get() : (ready ? End.READ_FAILED : End.READER_UNAVAILABLE);
            } finally { signalStop(); }
            journal.finish(result, ready, omitted, elapsed(started));
        } catch (IOException | RuntimeException unavailable) {
            result = End.STORAGE_FAILED;
        } finally {
            signalStop();
            Process child = process;
            if (child != null) {
                try {
                    if (!child.waitFor(2, TimeUnit.SECONDS)) child.destroyForcibly();
                } catch (InterruptedException interrupted) {
                    child.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
            timer.shutdownNow();
        }
        return result;
    }

    void stop(End reason) {
        if (!stop.compareAndSet(null, reason)) return;
        signalStop();
        ScheduledExecutorService current = timer;
        if (current != null && !current.isShutdown()) {
            try {
                current.schedule(() -> {
                    Process child = process;
                    if (child != null && child.isAlive()) child.destroyForcibly();
                }, 2, TimeUnit.SECONDS);
            } catch (java.util.concurrent.RejectedExecutionException finished) { /* Capture already ended. */ }
        }
    }

    private synchronized void signalStop() {
        Process child = process;
        if (child == null) return;
        try { child.getOutputStream().close(); }
        catch (IOException unavailable) { child.destroy(); }
    }

    private long elapsed(long start) { return Math.max(0, clock.getAsLong() - start); }
}
