package android.telephony;
import java.util.List;
public record UiccCardInfo(String eid, int card, int slot, boolean euicc, List<UiccPortInfo> ports) {
    public String getEid() { return eid; }
    public int getCardId() { return card; }
    public int getPhysicalSlotIndex() { return slot; }
    public boolean isEuicc() { return euicc; }
    public java.util.Collection<UiccPortInfo> getPorts() { return ports; }
}
