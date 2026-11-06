package com.lixingchi.flexibleesimtransfer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Redact before persistence, using the same reviewed rules as the host tools. */
final class DiagnosticEvents {
    static final int MAX_LINE = 16384;
    private static final Pattern LINE = Pattern.compile("[VDIWEF]/(FlexibleEsimTransfer|AndroidRuntime|EuiccGoogle|Euicc|NearbyConnections)\\s*\\(\\s*[0-9]+\\):\\s*(.*)");
    private static final Pattern PREFIX = Pattern.compile("^\\[[0-9]+\\] [\\w.$]+: ");
    private static final Pattern CONTEXT = Pattern.compile(" \\[CONTEXT [^\\r\\n]*\\]$");
    private static final Pattern BLOCKED = Pattern.compile("(?:Caused by: )?com\\.lixingchi\\.flexibleesimtransfer\\.Selection\\$Blocked: ([A-Z_]+)");
    private record Rule(String source, String event, String kind, Pattern pattern) { }
    private final List<Rule> rules = new ArrayList<>();

    DiagnosticEvents() throws IOException {
        var input = DiagnosticEvents.class.getResourceAsStream("/diagnostic-rules.tsv");
        if (input == null) throw new IOException("Rules unavailable");
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] fields = line.split("\t", -1);
                if (fields.length != 4 || !fields[0].matches("module|native|nearby")
                        || !fields[1].matches("[a-z_]+")) throw new IOException("Invalid rule");
                rules.add(new Rule(fields[0], fields[1], fields[2], Pattern.compile(fields[3])));
            }
        }
    }

    static boolean related(String line) { return line.length() <= MAX_LINE && LINE.matcher(line).matches(); }

    String parse(String line) {
        if (line.length() > MAX_LINE) return null;
        Matcher parsed = LINE.matcher(line);
        if (!parsed.matches()) return null;
        String tag = parsed.group(1), message = parsed.group(2);
        if (tag.equals("FlexibleEsimTransfer")) {
            String prefix = "com.lixingchi.flexibleesimtransfer: ";
            return message("module", message.startsWith(prefix) ? message.substring(prefix.length()) : message);
        }
        if (tag.equals("AndroidRuntime")) {
            Matcher blocked = BLOCKED.matcher(message);
            String result = blocked.matches() ? message("module", "blocked: " + blocked.group(1)) : null;
            return result == null ? null : result.replace("\"event\":\"blocked\"", "\"event\":\"routing_exception\"");
        }
        return tag.equals("NearbyConnections")
                ? message("nearby", CONTEXT.matcher(message).replaceFirst(""))
                : message("native", PREFIX.matcher(message).replaceFirst(""));
    }

    private String message(String source, String message) {
        for (Rule rule : rules) {
            if (!rule.source().equals(source)) continue;
            Matcher match = rule.pattern().matcher(message);
            if (!match.matches()) continue;
            StringBuilder json = new StringBuilder("{\"source\":\"").append(source)
                    .append("\",\"event\":\"").append(rule.event()).append('"');
            switch (rule.kind()) {
                case "codes", "unsigned_code" -> {
                    json.append(",\"codes\":[");
                    for (int i = 1; i <= match.groupCount(); i++) {
                        String value = match.group(i);
                        long number = value.startsWith("0x") ? Long.parseLong(value.substring(2), 16) : Long.parseLong(value);
                        long maximum = rule.kind().equals("unsigned_code") ? 0xffffffffL : Integer.MAX_VALUE;
                        if (number < Integer.MIN_VALUE || number > maximum) return null;
                        if (i > 1) json.append(',');
                        json.append(number);
                    }
                    json.append(']');
                }
                case "value", "result" -> {
                    String value = match.group(1);
                    if (!value.matches("[A-Za-z0-9_:;=.-]{1,100}")) return null;
                    if (rule.kind().equals("result") && value.contains("=")) {
                        long number = Long.parseLong(value.substring(value.indexOf('=') + 1));
                        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) return null;
                    }
                    json.append(",\"value\":\"").append(value).append('"');
                }
                case "smdp" -> json.append(",\"response_status\":").append(Integer.parseInt(match.group(1)))
                        .append(",\"http_status\":").append(Integer.parseInt(match.group(2)))
                        .append(",\"subject\":\"").append(match.group(3))
                        .append("\",\"reason\":\"").append(match.group(4)).append('"');
                case "phase" -> { }
                default -> throw new IllegalStateException("Invalid rule type");
            }
            return json.append('}').toString();
        }
        return null;
    }

    /** Discard an entire oversized line instead of parsing its truncated prefix as an event. */
    static String readLine(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        boolean oversized = false;
        int c;
        while ((c = reader.read()) != -1 && c != '\n') {
            if (line.length() < MAX_LINE) line.append((char) c);
            else oversized = true;
        }
        if (c == -1 && line.length() == 0) return null;
        if (oversized) return "";
        if (line.length() > 0 && line.charAt(line.length() - 1) == '\r') line.setLength(line.length() - 1);
        return line.toString();
    }
}
