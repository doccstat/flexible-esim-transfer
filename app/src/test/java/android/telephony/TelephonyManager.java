package android.telephony;
import java.util.List;
import com.lixingchi.flexibleesimtransfer.HookHarness;
public class TelephonyManager {
    public List<UiccCardInfo> cards;
    public List<UiccCardInfo> getUiccCardsInfo() {
        return HookHarness.call(TelephonyManager.class, "getUiccCardsInfo", this, new Class<?>[]{},
                new Object[]{}, (r, a) -> ((TelephonyManager) r).cards);
    }
}
