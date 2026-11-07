package com.android.internal.telephony.uicc;
import com.android.internal.telephony.uicc.euicc.EuiccPort;
public class UiccController {
    public static UiccController instance;
    public EuiccPort port;
    public Runnable duringLookup;
    public static UiccController getInstance() { return instance; }
    public EuiccPort getUiccPortForSlot(int slot, int index) {
        if (duringLookup != null) duringLookup.run();
        return slot == 1 && index == 0 ? port : null;
    }
}
