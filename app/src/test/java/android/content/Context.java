package android.content;
public class Context {
    public android.telephony.TelephonyManager telephony;
    public android.telephony.SubscriptionManager subscriptions = new android.telephony.SubscriptionManager();
    public <T> T getSystemService(Class<T> type) {
        return type.cast(type == android.telephony.SubscriptionManager.class ? subscriptions : telephony);
    }
}
