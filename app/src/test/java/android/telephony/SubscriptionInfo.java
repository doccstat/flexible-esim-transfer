package android.telephony;
public record SubscriptionInfo(int subscription, int card) {
    public int getSubscriptionId() { return subscription; }
    public int getCardId() { return card; }
}
