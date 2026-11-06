package com.lixingchi.flexibleesimtransfer;

import android.telephony.*;
import java.util.Map;

/** Deliberately not named after any R8 merged class. */
public class FixtureSlots {
    public TelephonyManager telephony;
    public TelephonyManager getTelephonyManager() { return telephony; }
    public int getActiveEuiccSlotId() { return noArgs("getActiveEuiccSlotId", 0); }
    public int getFirstActiveEuiccSlotId() { return noArgs("getFirstActiveEuiccSlotId", 0); }
    public int getFirstActiveEuiccLogicalSlotId() { return noArgs("getFirstActiveEuiccLogicalSlotId", 0); }
    public Map.Entry<Integer, Integer> getFirstActiveEuiccSlotIdAndPort() {
        return noArgs("getFirstActiveEuiccSlotIdAndPort", Map.entry(0, 0));
    }
    public int getPreferredEuiccSlotId(UiccSlotInfo[] slots) {
        return HookHarness.call(FixtureSlots.class, "getPreferredEuiccSlotId", this,
                new Class<?>[]{UiccSlotInfo[].class}, new Object[]{slots}, (r, a) -> 0);
    }
    public String getEidForSlot(int slot) {
        return HookHarness.call(FixtureSlots.class, "getEidForSlot", this,
                new Class<?>[]{int.class}, new Object[]{slot}, (r, a) -> {
                    int physical = (Integer) a[0];
                    return telephony.cards.stream().filter(c -> c.getPhysicalSlotIndex() == (physical == -1 ? 0 : physical))
                            .findFirst().orElseThrow().getEid();
                });
    }
    private <T> T noArgs(String name, T value) {
        return HookHarness.call(FixtureSlots.class, name, this, new Class<?>[]{}, new Object[]{}, (r, a) -> value);
    }
}
