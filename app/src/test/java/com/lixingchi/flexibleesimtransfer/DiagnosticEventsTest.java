package com.lixingchi.flexibleesimtransfer;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.Assert.*;

public class DiagnosticEventsTest {
    @Test public void sharedFixturesCoverCodesPhasesAndPrivatePayloads() throws Exception {
        DiagnosticEvents events = new DiagnosticEvents();
        try (var reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/diagnostic-cases.tsv"), StandardCharsets.UTF_8))) {
            int cases = 0;
            for (String line; (line = reader.readLine()) != null;) {
                if (line.startsWith("#") || line.isEmpty()) continue;
                String[] parts = line.split("\t");
                String result = events.parse(parts[0]);
                assertEquals(parts[0], parts[1].equals("DROP") ? null : parts[1], result);
                if (result != null) assertFalse(result.contains("SECRET"));
                cases++;
            }
            assertTrue(cases >= 40);
        }
    }

    @Test public void oversizedLinesAreDiscardedNotTruncatedIntoValidEvents() throws Exception {
        String valid = "I/FlexibleEsimTransfer(123): hooks: READY:LPA";
        String oversized = valid + " ".repeat(DiagnosticEvents.MAX_LINE) + "SECRET";
        DiagnosticEvents events = new DiagnosticEvents();
        assertNull(events.parse(oversized));
        var reader = new BufferedReader(new StringReader(oversized + "\n" + valid + "\r\n"));
        assertEquals("", DiagnosticEvents.readLine(reader));
        assertEquals(valid, DiagnosticEvents.readLine(reader));
        assertNull(DiagnosticEvents.readLine(reader));
    }

    @Test public void configurationNeverExportsTheDestinationAndKeepsPortText() {
        assertEquals(Map.of("saved_routing", "on", "saved_port", "007"),
                DiagnosticReport.configuration(true, "SECRET_FINGERPRINT", "007"));
        assertEquals("auto", DiagnosticReport.configuration(true, "", "").get("saved_port"));
        assertEquals("unknown", DiagnosticReport.configuration(false, "SECRET", "SECRET").get("saved_port"));
        assertEquals("omitted", DiagnosticReport.configuration(true, "SECRET", "89012345678901234567890123456789").get("saved_port"));
        assertEquals("unavailable", DiagnosticReport.version("https://SECRET_TOKEN"));
        assertEquals("26.44.31", DiagnosticReport.version("26.44.31"));
    }
}
