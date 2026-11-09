package android.telephony;
import java.util.List;
import com.lixingchi.flexibleesimtransfer.HookHarness;
public class TelephonyManager {
    public List<UiccCardInfo> cards;
    public RuntimeException failure;
    public int calls;
    public List<UiccCardInfo> getUiccCardsInfo() {
        return HookHarness.call(TelephonyManager.class, "getUiccCardsInfo", this, new Class<?>[]{},
                new Object[]{}, (r, a) -> {
                    TelephonyManager manager = (TelephonyManager) r;
                    manager.calls++;
                    if (manager.failure != null) throw manager.failure;
                    return manager.cards;
                });
    }
}
