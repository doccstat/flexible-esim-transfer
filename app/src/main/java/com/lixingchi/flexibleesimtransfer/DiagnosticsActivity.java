package com.lixingchi.flexibleesimtransfer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DiagnosticsActivity extends Activity {
    private static final int EXPORT = 1, NOTIFICATIONS = 2;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private Button start, stop, review, delete;
    private boolean busy;
    private byte[] exportSnapshot;
    private AlertDialog dialog;
    private final Runnable update = new Runnable() {
        @Override public void run() { render(); main.postDelayed(this, 500); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getLastNonConfigurationInstance() instanceof byte[] snapshot) exportSnapshot = snapshot;
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad, pad, pad);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        scroll.addView(body);
        text(body, R.string.diagnostics).setTextSize(24);
        text(body, R.string.diagnostics_explanation);
        status = text(body, R.string.diagnostics_idle);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        start = button(body, R.string.diagnostics_start, this::chooseDuration);
        stop = button(body, R.string.diagnostics_stop, () -> startService(new Intent(this, DiagnosticService.class).setAction(DiagnosticService.STOP)));
        review = button(body, R.string.diagnostics_review, this::review);
        delete = button(body, R.string.diagnostics_delete, () -> {
            dialog = new AlertDialog.Builder(this).setTitle(R.string.diagnostics_delete)
                    .setMessage(R.string.diagnostics_delete_confirm).setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.diagnostics_delete, (d, which) -> {
                        if (DiagnosticService.running) return;
                        try {
                            Files.deleteIfExists(DiagnosticReport.file(this));
                            DiagnosticService.status = R.string.diagnostics_idle;
                        } catch (IOException failure) { message(R.string.diagnostics_storage_failed); }
                        render();
                    }).show();
        });
        setContentView(scroll);
    }

    private void chooseDuration() {
        if (DiagnosticService.running) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
            return;
        }
        if (android.os.Process.myUid() / 100000 != 0 || !DiagnosticService.notificationsAllowed(this)) {
            message(R.string.diagnostics_permission);
            return;
        }
        int[] durations = {300, 900, 1800, 3600};
        String[] choices = new String[durations.length];
        for (int i = 0; i < durations.length; i++) choices[i] = getResources().getQuantityString(R.plurals.diagnostics_minutes, durations[i] / 60, durations[i] / 60);
        int[] selected = {-1};
        dialog = new AlertDialog.Builder(this).setTitle(R.string.diagnostics_duration)
                .setSingleChoiceItems(choices, -1, (d, which) -> {
                    selected[0] = which;
                    ((AlertDialog) d).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                }).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.diagnostics_start, (d, which) -> confirmStart(durations[selected[0]])).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
    }

    private void confirmStart(int seconds) {
        if (Files.exists(DiagnosticReport.file(this))) {
            dialog = new AlertDialog.Builder(this).setTitle(R.string.diagnostics_replace)
                    .setMessage(R.string.diagnostics_replace_confirm).setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.diagnostics_replace, (d, which) -> record(seconds, true)).show();
        } else record(seconds, false);
    }

    private void record(int seconds, boolean replace) {
        if (DiagnosticService.running) return;
        try {
            if (replace) Files.deleteIfExists(DiagnosticReport.file(this));
            startForegroundService(new Intent(this, DiagnosticService.class).setAction(DiagnosticService.START).putExtra("seconds", seconds));
        } catch (IOException | RuntimeException failure) { message(R.string.diagnostics_start_failed); }
        render();
    }

    private void review() {
        if (DiagnosticService.running || busy) return;
        busy = true;
        render();
        worker.execute(() -> {
            try {
                var file = DiagnosticReport.file(this);
                if (Files.size(file) > DiagnosticJournal.MAX_BYTES) throw new IOException("Report too large");
                byte[] snapshot = Files.readAllBytes(file);
                main.post(() -> {
                    busy = false;
                    if (isFinishing() || isDestroyed()) return;
                    TextView content = new TextView(this);
                    content.setText(new String(snapshot, StandardCharsets.UTF_8));
                    content.setTextIsSelectable(true);
                    ScrollView preview = new ScrollView(this);
                    preview.addView(content);
                    dialog = new AlertDialog.Builder(this).setTitle(R.string.diagnostics_review).setView(preview)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.diagnostics_export, (d, which) -> {
                                exportSnapshot = snapshot;
                                try {
                                    startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                            .addCategory(Intent.CATEGORY_OPENABLE).setType("application/x-ndjson")
                                            .putExtra(Intent.EXTRA_TITLE, "flexible-esim-diagnostics.jsonl"), EXPORT);
                                } catch (RuntimeException unavailable) {
                                    exportSnapshot = null;
                                    message(R.string.diagnostics_export_failed);
                                }
                            }).show();
                    render();
                });
            } catch (IOException | RuntimeException failure) {
                main.post(() -> { busy = false; message(R.string.diagnostics_storage_failed); render(); });
            }
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT) return;
        byte[] snapshot = exportSnapshot;
        exportSnapshot = null;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (snapshot == null) { message(R.string.diagnostics_export_failed); return; }
        var uri = data.getData();
        busy = true;
        render();
        worker.execute(() -> {
            int outcome = R.string.diagnostics_exported;
            try (var output = getContentResolver().openOutputStream(uri, "w")) {
                if (output == null) throw new IOException("Output unavailable");
                output.write(snapshot);
            } catch (IOException | RuntimeException failure) { outcome = R.string.diagnostics_export_failed; }
            int message = outcome;
            main.post(() -> { busy = false; message(message); render(); });
        });
    }

    @Override public Object onRetainNonConfigurationInstance() { return exportSnapshot; }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == NOTIFICATIONS) message(results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED
                ? R.string.diagnostics_permission_granted : R.string.diagnostics_permission);
    }

    private void render() {
        boolean running = DiagnosticService.running, saved = Files.exists(DiagnosticReport.file(this));
        status.setText(!running && saved && DiagnosticService.status == R.string.diagnostics_idle
                ? R.string.diagnostics_saved : DiagnosticService.status);
        start.setEnabled(!running && !busy);
        stop.setEnabled(running);
        review.setEnabled(saved && !running && !busy);
        delete.setEnabled(saved && !running && !busy);
    }

    private void message(int resource) {
        if (!isFinishing() && !isDestroyed()) dialog = new AlertDialog.Builder(this).setMessage(resource)
                .setPositiveButton(android.R.string.ok, null).show();
    }

    private TextView text(LinearLayout body, int resource) {
        TextView view = new TextView(this);
        view.setText(resource);
        view.setTextSize(16);
        view.setPadding(0, 0, 0, 20);
        body.addView(view);
        return view;
    }

    private Button button(LinearLayout body, int resource, Runnable action) {
        Button view = new Button(this);
        view.setText(resource);
        view.setOnClickListener(v -> action.run());
        body.addView(view);
        return view;
    }

    @Override protected void onResume() { super.onResume(); update.run(); }
    @Override protected void onPause() { main.removeCallbacks(update); super.onPause(); }
    @Override protected void onDestroy() {
        if (dialog != null) dialog.dismiss();
        worker.shutdown();
        super.onDestroy();
    }
}
