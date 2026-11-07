package com.lixingchi.flexibleesimtransfer;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Private report reads and explicit create-only exports; no filename rewriting. */
final class ModuleFiles {
    static String read(Path file, int maxBytes) throws Exception {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > maxBytes)
            throw new IllegalStateException();
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > maxBytes) throw new IllegalStateException();
        return new String(bytes, StandardCharsets.UTF_8);
    }
    static String readReport(Path report) throws Exception { return read(report, DiagnosticJournal.MAX_BYTES); }
    static Path export(Path report, Path directory, String name) throws Exception {
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")
                || name.indexOf('\0') >= 0) throw new IllegalArgumentException();
        Path target = directory.toRealPath().resolve(name);
        Files.write(target, readReport(report).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return target;
    }
    private ModuleFiles() { }
}
