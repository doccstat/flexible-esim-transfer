package com.android.internal.telephony.euicc;

import android.content.Context;
import android.os.Message;
import com.lixingchi.flexibleesimtransfer.HookHarness;

public class EuiccConnector {
    private final Context mContext;
    public final android.content.pm.ServiceInfo mSelectedComponent = new android.content.pm.ServiceInfo();
    public int calls;
    public Runnable work;
    public RuntimeException failure;
    public interface BaseEuiccCommandCallback { void onEuiccServiceUnavailable(); }
    public static class GetMetadataRequest { public BaseEuiccCommandCallback mCallback; }
    public static class GetDefaultListRequest { public BaseEuiccCommandCallback mCallback; }
    public static class DownloadRequest { public BaseEuiccCommandCallback mCallback; }
    public EuiccConnector(Context context) { mContext = context; mSelectedComponent.packageName = "com.google.android.euicc"; }
    public final ConnectedState connected = new ConnectedState();
    public class ConnectedState {
        public boolean processMessage(Message message) {
            return HookHarness.call(ConnectedState.class, "processMessage", this,
                    new Class<?>[]{Message.class}, new Object[]{message}, (o, a) -> {
                        calls++;
                        if (failure != null) throw failure;
                        if (work != null) work.run();
                        return true;
                    });
        }
        public void exit() {
            HookHarness.call(ConnectedState.class, "exit", this, new Class<?>[0], new Object[0], (o, a) -> null);
        }
    }
    public void unbind() {
        HookHarness.call(EuiccConnector.class, "unbind", this, new Class<?>[0], new Object[0], (o, a) -> null);
    }
    public void disconnected() { EuiccSession.get(mContext).endAllSessions(); connected.exit(); }
}
