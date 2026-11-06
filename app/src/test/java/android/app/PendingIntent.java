package android.app;
import android.content.Context;
import android.content.Intent;
public class PendingIntent {
    public static class CanceledException extends Exception { }
    public int calls;
    public int result = -999;
    public void send(Context context, int code, Intent intent) throws CanceledException { calls++; result = code; }
}
