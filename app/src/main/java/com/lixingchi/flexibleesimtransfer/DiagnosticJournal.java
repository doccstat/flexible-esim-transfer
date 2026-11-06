package com.lixingchi.flexibleesimtransfer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** One create-only report. Stop at the visible limit rather than silently discard older evidence. */
final class DiagnosticJournal implements AutoCloseable {
    static final int MAX_BYTES = 1024 * 1024;
    private static final int FOOTER_RESERVE = 4096;
    private final OutputStream output;
    private long bytes;

    DiagnosticJournal(Path file) throws IOException {
        output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    boolean append(String json, long elapsedMillis) throws IOException {
        byte[] line = line(json, elapsedMillis);
        if (bytes + line.length > MAX_BYTES - FOOTER_RESERVE) return false;
        write(line);
        return true;
    }

    void finish(DiagnosticRecorder.End reason, boolean ready, long omitted, long elapsedMillis) throws IOException {
        write(line("{\"event\":\"capture_end\",\"reason\":\"" + reason.name()
                + "\",\"reader_started\":" + ready + ",\"unrecognized_related_lines\":" + omitted + "}", elapsedMillis));
    }

    private static byte[] line(String json, long elapsedMillis) {
        if (!json.startsWith("{") || !json.endsWith("}") || json.indexOf('\n') >= 0 || json.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Expected one JSON record");
        return ("{\"elapsed_ms\":" + Math.max(0, elapsedMillis) + "," + json.substring(1) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private void write(byte[] line) throws IOException {
        output.write(line);
        output.flush();
        bytes += line.length;
    }

    @Override public void close() throws IOException { output.close(); }
}
