package com.android.internal.telephony.uicc.euicc.apdu;

import android.os.Handler;
import com.android.internal.telephony.euicc.EuiccSession;
import com.lixingchi.flexibleesimtransfer.HookHarness;

/** The modeled card loses its challenge on close; real card behavior needs a device trial. */
public class ApduSender {
    public int opens, closes;
    public RuntimeException failure;
    public Runnable duringClose;
    private boolean channel, challenge;
    public void send(RequestProvider request, ApduSenderResultCallback callback, Handler handler) {
        HookHarness.call(ApduSender.class, "send", this,
                new Class<?>[]{RequestProvider.class, ApduSenderResultCallback.class, Handler.class},
                new Object[]{request, callback, handler}, (o, a) -> {
                    if (failure != null) throw failure;
                    EuiccSession session = EuiccSession.instance;
                    boolean keepOpen = session.hasSession();
                    if (keepOpen) session.noteChannelOpen(this);
                    if (!channel) { channel = true; opens++; }
                    if (request.name().equals("challenge")) challenge = true;
                    int code = request.name().equals("info") || challenge ? 0 : 4;
                    if (!keepOpen) closeAnyOpenChannel();
                    callback.result = code;
                    return null;
                });
    }
    public int command(String name) {
        ApduSenderResultCallback callback = new ApduSenderResultCallback();
        send(new RequestProvider(name), callback, new Handler());
        return callback.result;
    }
    public void closeAnyOpenChannel() {
        if (duringClose != null) duringClose.run();
        if (channel) closes++;
        channel = false; challenge = false;
    }
}
