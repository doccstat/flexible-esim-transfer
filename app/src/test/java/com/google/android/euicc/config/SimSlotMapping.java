package com.google.android.euicc.config;

import java.util.List;
import com.lixingchi.flexibleesimtransfer.HookHarness;

public class SimSlotMapping {
    public List<Integer> embedded = List.of(0);
    public List<Integer> removable = List.of(1);
    public static class Base {
        public List<Integer> getEsimSlotIds() { return List.of(0); }
        public List<Integer> getPsimSlotIds() { return List.of(1); }
    }
    public List<Integer> getEsimSlotIds() {
        return HookHarness.call(SimSlotMapping.class, "getEsimSlotIds", this, new Class<?>[]{},
                new Object[]{}, (r, a) -> embedded);
    }
    public List<Integer> getPsimSlotIds() {
        return HookHarness.call(SimSlotMapping.class, "getPsimSlotIds", this, new Class<?>[]{},
                new Object[]{}, (r, a) -> removable);
    }
}
