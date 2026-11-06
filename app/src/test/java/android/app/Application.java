package android.app;
import android.content.Context;
import com.lixingchi.flexibleesimtransfer.HookHarness;
public class Application extends Context {
    public void attach(Context context) {
        HookHarness.call(Application.class, "attach", this, new Class<?>[]{Context.class},
                new Object[]{context}, (r, a) -> null);
    }
}
