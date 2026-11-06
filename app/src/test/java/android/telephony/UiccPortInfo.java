package android.telephony;
public record UiccPortInfo(int port, int logical, boolean active) {
    public int getPortIndex() { return port; }
    public int getLogicalSlotIndex() { return logical; }
    public boolean isActive() { return active; }
}
