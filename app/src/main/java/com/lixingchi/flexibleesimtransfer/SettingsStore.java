package com.lixingchi.flexibleesimtransfer;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class SettingsStore extends Application implements XposedServiceHelper.OnServiceListener {
    final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private XposedService service;
    private SharedPreferences prefs;
    Runnable changed;
    String confirmed = "";
    String confirmedPort = "";
    boolean confirmedPreserveSession;
    int error = R.string.disconnected;
    boolean saving;

    @Override public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
    }

    @Override public void onServiceBind(XposedService connected) {
        main.post(() -> {
            service = connected;
            try {
                prefs = connected.getRemotePreferences(Selection.PREFS);
                confirmed = prefs.getString(Selection.KEY, "");
                confirmedPort = prefs.getString(Selection.PORT, "");
                confirmedPreserveSession = prefs.getBoolean(Selection.PRESERVE_SESSION, false);
                if (!confirmed.isEmpty() && !Selection.configured(confirmed)) throw new IllegalStateException();
                Selection.parsePort(confirmedPort);
                error = 0;
            } catch (RuntimeException unavailable) {
                prefs = null;
                error = R.string.read_failed;
            }
            notifyChanged();
        });
    }

    @Override public void onServiceDied(XposedService disconnected) {
        main.post(() -> {
            if (service != disconnected) return;
            service = null;
            prefs = null;
            error = R.string.disconnected;
            notifyChanged();
        });
    }

    boolean ready() { return prefs != null && !saving; }

    void save(String fingerprint, String port, boolean preserveSession, Runnable onSuccess) {
        if (!ready() || (!fingerprint.isEmpty() && !Selection.configured(fingerprint))) return;
        Selection.parsePort(port);
        SharedPreferences target = prefs;
        saving = true;
        notifyChanged();
        worker.execute(() -> {
            boolean ok;
            try { ok = target.edit().putString(Selection.KEY, fingerprint)
                    .putString(Selection.PORT, port).putBoolean(Selection.PRESERVE_SESSION, preserveSession).commit(); }
            catch (RuntimeException unavailable) { ok = false; }
            final boolean saved = ok;
            main.post(() -> {
                saving = false;
                if (prefs == target) {
                    if (saved) {
                        confirmed = fingerprint;
                        confirmedPort = port;
                        confirmedPreserveSession = preserveSession;
                        error = 0;
                        onSuccess.run();
                    } else {
                        // RemotePreferences optimistically caches failed writes; do not trust that cache.
                        prefs = null;
                        error = R.string.save_failed;
                    }
                }
                notifyChanged();
            });
        });
    }

    private void notifyChanged() { if (changed != null) changed.run(); }
}
