package android.content;

import android.os.Bundle;

/** Minimal ordered-result plumbing; real Android permission enforcement is not simulated here. */
public abstract class BroadcastReceiver {
    public PendingResult pending;
    public boolean ordered = true;
    public abstract void onReceive(Context context, Intent intent);
    public boolean isOrderedBroadcast() { return ordered; }
    public PendingResult goAsync() { PendingResult result = pending; pending = null; return result; }
    public int getResultCode() { return pending.code; }
    public Bundle getResultExtras(boolean create) { return pending.extras; }

    public static final class PendingResult {
        public int code;
        public Bundle extras;
        public Runnable completed;
        public int finishes;
        public void setResult(int code, String data, Bundle extras) { this.code = code; this.extras = extras; }
        public void finish() {
            if (++finishes != 1) throw new AssertionError("Finished twice");
            completed.run();
        }
    }
}
