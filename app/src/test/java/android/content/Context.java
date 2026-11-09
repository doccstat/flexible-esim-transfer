package android.content;
public class Context {
    public static final int RECEIVER_EXPORTED = 2;
    public BroadcastReceiver receiver;
    public IntentFilter filter;
    public String senderPermission;
    public int receiverFlags, registrations;
    public boolean senderAuthorized = true, rejectRegistration, rejectSend, holdResult;
    public String hostPackage = "com.google.android.euicc";
    public String getPackageName() { return hostPackage; }
    public ClassLoader getClassLoader() { return getClass().getClassLoader(); }
    public android.content.pm.ApplicationInfo getApplicationInfo() { return new android.content.pm.ApplicationInfo(); }
    public Intent lastIntent;
    public Runnable deliverResult;
    public android.telephony.TelephonyManager telephony;
    public android.telephony.SubscriptionManager subscriptions = new android.telephony.SubscriptionManager();
    public <T> T getSystemService(Class<T> type) {
        return type.cast(type == android.telephony.SubscriptionManager.class ? subscriptions : telephony);
    }
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, String permission,
                                   android.os.Handler handler, int flags) {
        if (rejectRegistration) throw new SecurityException("Rejected");
        this.receiver = receiver; this.filter = filter; senderPermission = permission; receiverFlags = flags;
        registrations++;
        return null;
    }
    public void sendOrderedBroadcast(Intent intent, String permission, BroadcastReceiver resultReceiver,
                                     android.os.Handler handler, int code, String data, android.os.Bundle extras) {
        if (rejectSend) throw new SecurityException("Rejected");
        lastIntent = intent;
        BroadcastReceiver.PendingResult result = new BroadcastReceiver.PendingResult();
        result.setResult(code, data, extras);
        deliverResult = () -> {
            resultReceiver.pending = result;
            resultReceiver.onReceive(this, intent);
        };
        result.completed = () -> { if (!holdResult) deliverResult.run(); };
        if (receiver != null && senderAuthorized && hostPackage.equals(intent.getPackage())
                && filter.action.equals(intent.getAction())) {
            receiver.pending = result;
            receiver.onReceive(this, intent);
            if (receiver.pending != null) result.finish();
        } else result.finish();
    }
}
