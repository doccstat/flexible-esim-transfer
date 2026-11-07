package com.android.internal.telephony.uicc.euicc;
import com.android.internal.telephony.uicc.euicc.apdu.ApduSender;
public class EuiccPort {
    private final ApduSender mApduSender;
    public EuiccPort(ApduSender sender) { mApduSender = sender; }
}
