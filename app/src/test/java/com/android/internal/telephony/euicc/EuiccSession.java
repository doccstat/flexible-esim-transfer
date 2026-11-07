package com.android.internal.telephony.euicc;

import android.content.Context;
import com.android.internal.telephony.uicc.euicc.apdu.ApduSender;
import com.lixingchi.flexibleesimtransfer.HookHarness;
import java.util.HashSet;
import java.util.Set;

/** Models the native shared session set and channel cleanup, not cryptography. */
public class EuiccSession {
    public static EuiccSession instance;
    private final Set<String> mSessions = new HashSet<>();
    private final Set<ApduSender> mApduSenders = new HashSet<>();
    public boolean optimized, rejectStart;
    public static EuiccSession get(Context context) {
        if (instance == null) instance = new EuiccSession();
        return instance;
    }
    private boolean optimizeApduSender() {
        return HookHarness.call(EuiccSession.class, "optimizeApduSender", this,
                new Class<?>[0], new Object[0], (o, a) -> optimized);
    }
    public void startSession(String id) {
        if (optimizeApduSender() && !rejectStart) mSessions.add(id);
    }
    public boolean hasSession() {
        return HookHarness.call(EuiccSession.class, "hasSession", this,
                new Class<?>[0], new Object[0], (o, a) -> !mSessions.isEmpty());
    }
    public void noteChannelOpen(ApduSender sender) { if (!mSessions.isEmpty()) mApduSenders.add(sender); }
    public void endSession(String id) {
        HookHarness.call(EuiccSession.class, "endSession", this,
                new Class<?>[]{String.class}, new Object[]{id}, (o, a) -> {
                    if (mSessions.remove(id) && mSessions.isEmpty()) close();
                    return null;
                });
    }
    public void endAllSessions() { mSessions.clear(); close(); }
    public boolean containsOwnedSession() {
        return mSessions.stream().anyMatch(s -> s.startsWith("com.lixingchi.flexibleesimtransfer:"));
    }
    private void close() {
        for (ApduSender sender : mApduSenders) sender.closeAnyOpenChannel();
        mApduSenders.clear();
    }
}
