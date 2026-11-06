package com.lixingchi.flexibleesimtransfer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class InventoryBridge {
    static final String HOST = "com.google.android.euicc";
    static final String ACTION = "com.lixingchi.flexibleesimtransfer.READ_INVENTORY";
    static final String PERMISSION = "com.lixingchi.flexibleesimtransfer.permission.READ_INVENTORY";
    static final int UNAVAILABLE = 0, OK = 1, FAILED = 2, INVALID = 3, TIMEOUT = 4;
    static final long TIMEOUT_MS = 10_000;

    static void register(Context host, Supplier<List<CardInventory.Card>> reader,
                         Executor worker, Consumer<String> event) {
        host.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!ACTION.equals(intent.getAction()) || !isOrderedBroadcast()) return;
                PendingResult reply = goAsync();
                try {
                    worker.execute(() -> {
                        try {
                            List<CardInventory.Card> cards = reader.get();
                            reply.setResult(OK, null, CardInventory.encode(cards));
                            event.accept(cards.isEmpty() ? "EMPTY" : "READY");
                        } catch (Throwable unavailable) {
                            reply.setResult(FAILED, null, null);
                            event.accept("FAILED");
                        } finally { reply.finish(); }
                    });
                } catch (RuntimeException unavailable) {
                    reply.setResult(FAILED, null, null);
                    reply.finish();
                }
            }
        }, new IntentFilter(ACTION), PERMISSION, null, Context.RECEIVER_EXPORTED);
    }

    record Result(int status, List<CardInventory.Card> cards) { }

    /** Each request owns its completion/timeout; cancelled or late responses cannot replace a newer scan. */
    static final class Query {
        private final Handler handler;
        private Consumer<Result> callback;
        private final Runnable timeout = () -> finish(new Result(TIMEOUT, null));

        Query(Handler handler) { this.handler = handler; }

        void start(Context context, Consumer<Result> callback) {
            if (this.callback != null) throw new IllegalStateException("Query already started");
            this.callback = callback;
            handler.postDelayed(timeout, TIMEOUT_MS);
            try {
                context.sendOrderedBroadcast(new Intent(ACTION).setPackage(HOST), null,
                        new BroadcastReceiver() {
                            @Override public void onReceive(Context context, Intent intent) {
                                if (Query.this.callback == null) return;
                                if (getResultCode() != OK) {
                                    finish(new Result(getResultCode() == FAILED ? FAILED : UNAVAILABLE, null));
                                    return;
                                }
                                final List<CardInventory.Card> cards;
                                try { cards = CardInventory.decode(getResultExtras(false)); }
                                catch (RuntimeException invalid) { finish(new Result(INVALID, null)); return; }
                                finish(new Result(OK, cards));
                            }
                        }, handler, UNAVAILABLE, null, null);
            } catch (RuntimeException unavailable) { finish(new Result(UNAVAILABLE, null)); }
        }

        void cancel() {
            callback = null;
            handler.removeCallbacks(timeout);
        }

        private void finish(Result result) {
            Consumer<Result> current = callback;
            cancel();
            if (current != null) current.accept(result);
        }
    }

    private InventoryBridge() { }
}
