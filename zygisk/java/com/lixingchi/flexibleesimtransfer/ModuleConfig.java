package com.lixingchi.flexibleesimtransfer;

import java.util.Map;

/** Bounded, exact format shared by the root controller and read-only native companion. */
record ModuleConfig(String destination, String port, boolean preserve) {
    ModuleConfig {
        if (destination == null || (!destination.isEmpty() && !Selection.configured(destination)))
            throw new IllegalArgumentException("Invalid destination");
        Selection.parsePort(port);
        if (port.length() > 10) throw new IllegalArgumentException("Invalid port");
        if (destination.isEmpty() && (!port.isEmpty() || preserve)) throw new IllegalArgumentException("Invalid off state");
    }
    static ModuleConfig parse(String text) {
        if (text == null || text.length() > 128) throw new Selection.Blocked("SETTINGS_UNAVAILABLE");
        String[] parts = text.split("\n", -1);
        if (parts.length != 5 || !parts[0].equals("FET1") || !parts[4].isEmpty()
                || (!parts[3].equals("0") && !parts[3].equals("1")))
            throw new Selection.Blocked("SETTINGS_UNAVAILABLE");
        try { return new ModuleConfig(parts[1], parts[2], parts[3].equals("1")); }
        catch (RuntimeException invalid) { throw new Selection.Blocked("SETTINGS_UNAVAILABLE"); }
    }
    String encode() { return "FET1\n" + destination + "\n" + port + "\n" + (preserve ? "1" : "0") + "\n"; }
    Map<String, ?> values() { return Map.of(Selection.KEY, destination, Selection.PORT, port, Selection.PRESERVE_SESSION, preserve); }
}
