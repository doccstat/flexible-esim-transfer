package android.util;
import com.lixingchi.flexibleesimtransfer.HookHarness;
public class Log {
    public static int i(String tag, String message) { return println(4, tag, message); }
    public static int println(int priority, String tag, String message) {
        HookHarness.logs.add(tag + ": " + message);
        return 0;
    }
}
