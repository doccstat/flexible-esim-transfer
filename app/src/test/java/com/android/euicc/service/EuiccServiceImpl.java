package com.android.euicc.service;

import android.telephony.euicc.DownloadableSubscription;
import com.lixingchi.flexibleesimtransfer.HookHarness;

public class EuiccServiceImpl {
    public static final class DownloadResult {
        public final int result;
        public DownloadResult(int result, int resolvableErrors, int cardId) { this.result = result; }
        public int getResult() { return result; }
    }
    public static final class MetadataResult {
        public final int result;
        public MetadataResult(int result, DownloadableSubscription subscription) { this.result = result; }
        public int getResult() { return result; }
    }
    public int calls;
    public int lastSlot;
    public int lastPort;
    public int result;
    public DownloadResult onDownloadSubscription(int slot, int port, DownloadableSubscription subscription,
                                                 boolean enable, boolean deactivate, android.os.Bundle extras) {
        return HookHarness.call(EuiccServiceImpl.class, "onDownloadSubscription", this,
                new Class<?>[]{int.class, int.class, DownloadableSubscription.class, boolean.class, boolean.class, android.os.Bundle.class},
                new Object[]{slot, port, subscription, enable, deactivate, extras}, (r, a) -> {
                    calls++; lastSlot = (Integer) a[0]; lastPort = (Integer) a[1];
                    return new DownloadResult(result, 0, -1);
                });
    }
    public MetadataResult onGetDownloadableSubscriptionMetadata(int slot, DownloadableSubscription subscription, boolean deactivate) {
        return HookHarness.call(EuiccServiceImpl.class, "onGetDownloadableSubscriptionMetadata", this,
                new Class<?>[]{int.class, DownloadableSubscription.class, boolean.class},
                new Object[]{slot, subscription, deactivate}, (r, a) -> { calls++; return new MetadataResult(result, subscription); });
    }
}
