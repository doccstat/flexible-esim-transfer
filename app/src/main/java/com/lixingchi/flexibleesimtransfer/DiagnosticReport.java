package com.lixingchi.flexibleesimtransfer;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;

final class DiagnosticReport {
    private static final String[] PACKAGES = {"com.lixingchi.flexibleesimtransfer", "com.google.android.euicc", "com.google.android.gms", "com.android.phone"};

    static Path file(Context context) { return context.getNoBackupFilesDir().toPath().resolve("diagnostics.jsonl"); }

    static String version(String value) {
        return value != null && value.matches("[A-Za-z0-9._()+-]{1,100}") ? value : "unavailable";
    }

    static Map<String, String> configuration(boolean available, String destination, String port, boolean preserveSession) {
        if (!available) return Map.of("saved_routing", "unknown", "saved_port", "unknown", "saved_preserve_card_session", "unknown");
        String safePort = port.isEmpty() ? "auto" : port.matches("[0-9]{1,10}") ? port : "omitted";
        return Map.of("saved_routing", destination.isEmpty() ? "off" : "on", "saved_port", safePort,
                "saved_preserve_card_session", preserveSession ? "on" : "off");
    }

    static String header(Context context, int seconds, Map<String, String> configuration) throws JSONException {
        JSONObject versions = new JSONObject();
        for (String name : PACKAGES) {
            JSONObject item = new JSONObject();
            try {
                var info = context.getPackageManager().getPackageInfo(name, 0);
                item.put("name", version(info.versionName)).put("code", info.getLongVersionCode());
            } catch (PackageManager.NameNotFoundException unavailable) { item.put("status", "unavailable"); }
            versions.put(name, item);
        }
        return new JSONObject().put("event", "capture_start").put("schema", 1)
                .put("utc", Instant.now().toString()).put("duration_seconds", seconds)
                .put("max_bytes", DiagnosticJournal.MAX_BYTES).put("installed_versions", versions)
                .put("android", new JSONObject().put("sdk", Build.VERSION.SDK_INT)
                        .put("release", version(Build.VERSION.RELEASE)).put("security_patch", version(Build.VERSION.SECURITY_PATCH)))
                .put("configuration", new JSONObject(configuration))
                .put("scope", "owner_user_module_sim_manager_play_services_phone")
                .put("initial_tail_may_predate_capture", true)
                .put("note", "Saved settings and installed versions are not proof of loaded hooks. Elapsed times are reader receipt times. Unknown or suppressed events are not captured. Nearby events may belong to other features in Play services. An absent capture_end means interruption.")
                .toString();
    }

    static int[] uids(Context context) throws PackageManager.NameNotFoundException {
        ArrayList<Integer> result = new ArrayList<>();
        for (String name : PACKAGES) result.add(context.getPackageManager().getPackageUid(name, 0));
        return result.stream().mapToInt(Integer::intValue).toArray();
    }

    private DiagnosticReport() { }
}
