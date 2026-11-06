package android.telephony;
import java.util.List;
public class SubscriptionManager {
    public List<SubscriptionInfo> subscriptions = List.of();
    public List<SubscriptionInfo> getAvailableSubscriptionInfoList() { return subscriptions; }
}
