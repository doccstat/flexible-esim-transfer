package com.lixingchi.flexibleesimtransfer;

import android.content.Context;
import com.lixingchi.flexibleesimtransfer.standalone.BuildConfig;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Root-only fixed commands for WebUI; no shell evaluation or arbitrary private-file access. */
public final class ModuleControl {
    private static final Path STATE = Paths.get("/data/adb/flexible-esim-transfer");
    private static final Path REPORT = STATE.resolve("diagnostics.jsonl");
    private static final Path STOP = STATE.resolve("record.stop");
    private static final Path ACTIVE = STATE.resolve("record.active");
    private static final Path LOCK = STATE.resolve("record.lock");

    public static void main(String[] args) {
        int result = 0;
        try {
            if (android.os.Process.myUid() != 0 || args.length < 1 || !Files.isDirectory(STATE, LinkOption.NOFOLLOW_LINKS))
                throw new SecurityException();
            if (args[0].equals("record")) { record(Integer.parseInt(args[1])); System.exit(0); }
            byte[] input = System.in.readNBytes(4097);
            if (input.length > 4096) throw new IllegalArgumentException();
            JSONObject request = input.length == 0 ? new JSONObject() : new JSONObject(new String(input, StandardCharsets.UTF_8));
            JSONObject response;
            try (var channel = FileChannel.open(STATE.resolve("control.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var ignored = channel.lock()) {
                response = command(args[0], request);
            }
            System.out.println(response.put("ok", true));
        } catch (Exception unavailable) {
            System.out.println("{\"ok\":false,\"error\":\"UNAVAILABLE\"}");
            result = 1;
        }
        System.exit(result);
    }

    private static JSONObject command(String action, JSONObject request) throws Exception {
        switch (action) {
            case "status": {
                ModuleConfig config = config();
                JSONObject result = new JSONObject().put("routing", !config.destination().isEmpty())
                        .put("port", config.port()).put("preserve", config.preserve())
                        .put("recording", recording()).put("report", Files.isRegularFile(REPORT))
                        .put("readerReady", recording() && Files.isRegularFile(ACTIVE) && ModuleFiles.read(ACTIVE, 128).equals("ready"))
                        .put("version", BuildConfig.MODULE_NAME).put("code", BuildConfig.MODULE_CODE);
                if (Files.isRegularFile(STATE.resolve("record.result"))) {
                    String end = ModuleFiles.read(STATE.resolve("record.result"), 128);
                    if (end.matches("STOPPED|DURATION_REACHED|LIMIT_REACHED|READER_UNAVAILABLE|READ_FAILED|STORAGE_FAILED")) result.put("recordResult", end);
                }
                return result;
            }
            case "inventory": return new JSONObject().put("cards", InventorySocket.query(request.optBoolean("reveal", false)));
            case "save": {
                String eid = request.optString("eid", "");
                String fingerprint = request.getString("fingerprint");
                String port = request.getString("port");
                boolean manual = request.getBoolean("manual");
                if (manual) fingerprint = Selection.fingerprint(eid);
                ModuleConfig next = new ModuleConfig(fingerprint, port, request.getBoolean("preserve"));
                if (next.destination().isEmpty()) throw new IllegalArgumentException();
                if (!manual) {
                    JSONArray inventory = InventorySocket.query(true);
                    var cards = new java.util.ArrayList<Selection.Card>();
                    for (int i = 0; i < inventory.length(); i++) {
                        var card = inventory.getJSONObject(i); var ports = new java.util.ArrayList<Selection.Port>();
                        var rawPorts = card.getJSONArray("ports");
                        for (int j = 0; j < rawPorts.length(); j++) {
                            var p = rawPorts.getJSONObject(j);
                            ports.add(new Selection.Port(p.getInt("index"), p.getInt("logicalSlot"), p.getBoolean("active")));
                        }
                        cards.add(new Selection.Card(card.getString("fingerprint"), card.getInt("cardId"), card.getInt("slot"), true, ports));
                    }
                    Selection.resolve(next.destination(), port, cards);
                } else if (!request.getBoolean("confirmUnverified")) throw new IllegalArgumentException();
                atomic(STATE.resolve("config"), next.encode());
                return new JSONObject().put("restartRequired", true);
            }
            case "off": {
                atomic(STATE.resolve("config"), new ModuleConfig("", "", false).encode());
                return new JSONObject().put("restartRequired", true);
            }
            case "start": {
                int seconds = request.getInt("seconds");
                if (seconds != 300 && seconds != 900 && seconds != 1800 && seconds != 3600) throw new IllegalArgumentException();
                if (recording() || Files.exists(REPORT)) throw new IllegalStateException();
                Files.deleteIfExists(STOP); Files.deleteIfExists(ACTIVE); Files.deleteIfExists(STATE.resolve("record.result"));
                RecordingWorker.builder(seconds).start();
                long deadline = SystemClock.elapsedRealtime() + 5000;
                while (!Files.exists(ACTIVE) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
                if (!Files.exists(ACTIVE)) { atomic(STOP, "stop"); throw new IllegalStateException(); }
                return new JSONObject().put("recording", true).put("readerReady", ModuleFiles.read(ACTIVE, 128).equals("ready"));
            }
            case "stop": atomic(STOP, "stop"); return new JSONObject();
            case "delete": {
                if (recording()) throw new IllegalStateException();
                Files.deleteIfExists(REPORT); Files.deleteIfExists(STATE.resolve("record.result"));
                return new JSONObject();
            }
            case "review": {
                return new JSONObject().put("report", ModuleFiles.readReport(REPORT));
            }
            case "export": {
                if (recording()) throw new IllegalStateException();
                String name = request.getString("name");
                ModuleFiles.export(REPORT, Paths.get("/storage/emulated/0/Download"), name);
                return new JSONObject().put("exported", "Download/" + name);
            }
            default: throw new IllegalArgumentException();
        }
    }
    private static ModuleConfig config() throws Exception { return ModuleConfig.parse(ModuleFiles.read(STATE.resolve("config"), 128)); }
    private static boolean recording() throws Exception {
        try (var channel = FileChannel.open(LOCK, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) { return lock == null; }
    }
    private static void atomic(Path destination, String value) throws Exception {
        Path temp = Files.createTempFile(STATE, ".write-", "", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.write(temp, value.getBytes(StandardCharsets.UTF_8));
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    private static Context context() throws Exception {
        android.os.Looper.prepareMainLooper();
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object instance = thread.getMethod("systemMain").invoke(null);
        return (Context) thread.getMethod("getSystemContext").invoke(instance);
    }
    private static void record(int seconds) throws Exception {
        if (!DiagnosticRecorder.validDuration(seconds)) throw new IllegalArgumentException();
        try (var channel = FileChannel.open(LOCK, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null || Files.exists(REPORT)) return;
            atomic(ACTIVE, "starting");
            long started = SystemClock.elapsedRealtime();
            var timer = Executors.newSingleThreadScheduledExecutor();
            var initialization = timer.schedule(() -> {
                try { atomic(STATE.resolve("record.result"), "READER_UNAVAILABLE"); Files.deleteIfExists(ACTIVE); }
                catch (Exception ignored) { }
                System.exit(1);
            }, 30, TimeUnit.SECONDS);
            try {
                Context context = context();
                ModuleConfig config = config();
                var header = new JSONObject(DiagnosticReport.header(context, seconds,
                        DiagnosticReport.configuration(true, config.destination(), config.port(), config.preserve())))
                        .put("backend", "zygisk").put("module_version", BuildConfig.MODULE_NAME).put("module_code", BuildConfig.MODULE_CODE);
                String[] packages = {"com.android.phone", "com.google.android.euicc", "com.google.android.gms"};
                int[] uids = new int[3];
                for (int i = 0; i < packages.length; i++) uids[i] = context.getPackageManager().getPackageUid(packages[i], 0);
                DiagnosticRecorder recorder = new DiagnosticRecorder(command -> new ProcessBuilder("/system/bin/sh", "-c", command)
                        .redirectErrorStream(true).start(), SystemClock::elapsedRealtime);
                initialization.cancel(false);
                long elapsed = SystemClock.elapsedRealtime() - started;
                int remaining = Math.max(1, seconds - (int) (elapsed / 1000));
                timer.schedule(() -> {
                    try { if (!ModuleFiles.read(ACTIVE, 128).equals("ready")) recorder.stop(DiagnosticRecorder.End.READER_UNAVAILABLE); }
                    catch (Exception unavailable) { recorder.stop(DiagnosticRecorder.End.READER_UNAVAILABLE); }
                }, Math.max(0, 30000 - elapsed), TimeUnit.MILLISECONDS);
                timer.scheduleWithFixedDelay(() -> { if (Files.exists(STOP)) recorder.stop(DiagnosticRecorder.End.STOPPED); }, 0, 250, TimeUnit.MILLISECONDS);
                var end = recorder.run(REPORT, header.toString(), remaining, uids, () -> {
                    try { atomic(ACTIVE, "ready"); } catch (Exception ignored) { recorder.stop(DiagnosticRecorder.End.STORAGE_FAILED); }
                });
                atomic(STATE.resolve("record.result"), end.name());
            } finally { timer.shutdownNow(); Files.deleteIfExists(ACTIVE); }
        }
    }
}
