package com.lixingchi.flexibleesimtransfer;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

public final class DiagnosticService extends Service {
    static final String START = "record", STOP = "stop";
    private static final String CHANNEL = "diagnostics";
    private static final int NOTIFICATION = 1;
    static volatile boolean running;
    static volatile int status = R.string.diagnostics_idle;
    private final Handler main = new Handler(Looper.getMainLooper());
    private DiagnosticRecorder recorder;

    static boolean notificationsAllowed(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                && manager.areNotificationsEnabled() && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }
        if (STOP.equals(intent.getAction())) {
            if (recorder != null) {
                status = R.string.diagnostics_stopping;
                recorder.stop(DiagnosticRecorder.End.STOPPED);
            } else stopSelf(startId);
            return START_NOT_STICKY;
        }
        int seconds = intent.getIntExtra("seconds", 0);
        if (!START.equals(intent.getAction()) || !DiagnosticRecorder.validDuration(seconds) || running) return START_NOT_STICKY;
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (android.os.Process.myUid() / 100000 != 0 || !notificationsAllowed(this)) {
            status = R.string.diagnostics_permission;
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        try {
            notifications.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.diagnostics), NotificationManager.IMPORTANCE_LOW));
            status = R.string.diagnostics_starting;
            startForeground(NOTIFICATION, notification());
            int[] uids = DiagnosticReport.uids(this);
            SettingsStore settings = (SettingsStore) getApplication();
            String header = DiagnosticReport.header(this, seconds,
                    DiagnosticReport.configuration(settings.ready(), settings.confirmed, settings.confirmedPort,
                            settings.confirmedPreserveSession));
            recorder = new DiagnosticRecorder(android.os.SystemClock::elapsedRealtime);
            running = true;
            new Thread(() -> {
                DiagnosticRecorder.End end = recorder.run(DiagnosticReport.file(this), header, seconds, uids, () -> main.post(() -> {
                    if (status != R.string.diagnostics_stopping) {
                        status = R.string.diagnostics_recording;
                        try { notifications.notify(NOTIFICATION, notification()); }
                        catch (RuntimeException unavailable) { recorder.stop(DiagnosticRecorder.End.STOPPED); }
                    }
                }));
                main.post(() -> {
                    running = false;
                    status = switch (end) {
                        case STOPPED -> R.string.diagnostics_stopped;
                        case DURATION_REACHED -> R.string.diagnostics_expired;
                        case LIMIT_REACHED -> R.string.diagnostics_full;
                        case READER_UNAVAILABLE -> R.string.diagnostics_reader_failed;
                        case READ_FAILED -> R.string.diagnostics_read_failed;
                        case STORAGE_FAILED -> R.string.diagnostics_storage_failed;
                    };
                    recorder = null;
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                });
            }, "redacted-diagnostics").start();
        } catch (Exception unavailable) {
            status = R.string.diagnostics_start_failed;
            running = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(getString(R.string.diagnostics)).setContentText(getString(status))
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, DiagnosticsActivity.class), flags))
                .addAction(new Notification.Action.Builder(null, getString(R.string.diagnostics_stop),
                        PendingIntent.getService(this, 0, new Intent(this, DiagnosticService.class).setAction(STOP), flags)).build())
                .setOngoing(true).setOnlyAlertOnce(true).build();
    }

    @Override public void onDestroy() {
        if (recorder != null) recorder.stop(DiagnosticRecorder.End.STOPPED);
        super.onDestroy();
    }
}
