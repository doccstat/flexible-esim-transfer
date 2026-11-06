package android.telephony.euicc;

import android.app.PendingIntent;
import com.lixingchi.flexibleesimtransfer.HookHarness;
import java.util.HashMap;
import java.util.Map;

public class EuiccManager {
    public static final Map<Integer, String> eids = new HashMap<>();
    public static int downloadedCard = -1;
    public static int switchedCard = -1;
    public static int switchedSubscription = -1;
    public static int continuedCard = -1;
    public static int originalCalls;
    public static Throwable failure;
    public final int card;
    public EuiccManager(int card) { this.card = card; }
    public EuiccManager createForCardId(int id) {
        return HookHarness.call(EuiccManager.class, "createForCardId", this, new Class<?>[]{int.class},
                new Object[]{id}, (r, a) -> new EuiccManager((Integer) a[0]));
    }
    public String getEid() {
        return HookHarness.call(EuiccManager.class, "getEid", this, new Class<?>[]{}, new Object[]{},
                (r, a) -> { originalCalls++; return eids.get(((EuiccManager) r).card); });
    }
    public void switchToSubscription(int subscription, PendingIntent callback) {
        HookHarness.call(EuiccManager.class, "switchToSubscription", this,
                new Class<?>[]{int.class, PendingIntent.class}, new Object[]{subscription, callback}, (r, a) -> {
                    switchedCard = ((EuiccManager) r).card; switchedSubscription = (Integer) a[0]; return null;
                });
    }
    public void continueOperation(android.content.Intent intent, android.os.Bundle extras) {
        HookHarness.call(EuiccManager.class, "continueOperation", this,
                new Class<?>[]{android.content.Intent.class, android.os.Bundle.class}, new Object[]{intent, extras}, (r, a) -> {
                    continuedCard = ((EuiccManager) r).card; return null;
                });
    }
    public void downloadSubscription(DownloadableSubscription subscription, boolean enable, PendingIntent callback) {
        HookHarness.call(EuiccManager.class, "downloadSubscription", this,
                new Class<?>[]{DownloadableSubscription.class, boolean.class, PendingIntent.class},
                new Object[]{subscription, enable, callback}, (r, a) -> {
                    originalCalls++;
                    if (failure != null) throw failure;
                    downloadedCard = ((EuiccManager) r).card;
                    return null;
                });
    }
}
