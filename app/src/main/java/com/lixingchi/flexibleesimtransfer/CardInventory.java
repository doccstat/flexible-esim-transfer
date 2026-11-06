package com.lixingchi.flexibleesimtransfer;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.telephony.UiccCardInfo;
import android.telephony.UiccPortInfo;

import java.util.ArrayList;
import java.util.List;

/** Only eUICC identity and addressing: never transport ICCIDs or subscription information. */
final class CardInventory {
    record Card(String eid, int cardId, int slot, boolean removable, List<Selection.Port> ports) {
        String fingerprint() {
            try { return Selection.fingerprint(eid); }
            catch (IllegalArgumentException unreadable) { return ""; }
        }
        Selection.Card selection() {
            return new Selection.Card(fingerprint(), cardId, slot, true, ports);
        }
        @Override public String toString() { return "eUICC inventory (identifiers omitted)"; }
    }

    @SuppressLint("MissingPermission") // Called in the privileged LPA, not the settings app.
    static List<Card> fromTelephony(List<UiccCardInfo> inventory) {
        if (inventory == null) throw new Selection.Blocked("INVENTORY_UNAVAILABLE");
        List<Card> result = new ArrayList<>();
        for (UiccCardInfo card : inventory) {
            if (card == null || !card.isEuicc()) continue;
            String eid = card.getEid();
            try { Selection.fingerprint(eid); }
            catch (IllegalArgumentException unreadable) { eid = ""; }
            List<Selection.Port> ports = new ArrayList<>();
            for (UiccPortInfo port : card.getPorts())
                ports.add(new Selection.Port(port.getPortIndex(), port.getLogicalSlotIndex(), port.isActive()));
            result.add(new Card(eid, card.getCardId(), card.getPhysicalSlotIndex(), card.isRemovable(), List.copyOf(ports)));
        }
        return List.copyOf(result);
    }

    static List<Selection.Card> selections(List<Card> cards) {
        List<Selection.Card> result = new ArrayList<>();
        for (Card card : cards) result.add(card.selection());
        return result;
    }

    static Selection.Target choose(List<Card> cards, Card card, Selection.Port port) {
        if (cards == null || !cards.contains(card) || !card.ports().contains(port))
            throw new Selection.Blocked("INVENTORY_UNAVAILABLE");
        if (!port.active()) throw new Selection.Blocked("PORT_INACTIVE");
        return Selection.resolve(card.fingerprint(), Integer.toString(port.index()), selections(cards));
    }

    // A manual fallback remains possible when the scan has no matching readable EID.
    static boolean verifyManual(List<Card> cards, String eid, String port) {
        String fingerprint = Selection.fingerprint(eid);
        Selection.parsePort(port);
        if (cards == null || cards.stream().noneMatch(card -> fingerprint.equals(card.fingerprint()))) return false;
        Selection.resolve(fingerprint, port, selections(cards));
        return true;
    }

    static Bundle encode(List<Card> cards) {
        Bundle result = new Bundle();
        result.putInt("schema", 1);
        ArrayList<Bundle> encoded = new ArrayList<>();
        for (Card card : cards) {
            Bundle item = new Bundle();
            item.putString("eid", card.eid());
            item.putInt("cardId", card.cardId());
            item.putInt("slot", card.slot());
            item.putBoolean("removable", card.removable());
            ArrayList<Bundle> ports = new ArrayList<>();
            for (Selection.Port port : card.ports()) {
                Bundle p = new Bundle();
                p.putInt("index", port.index());
                p.putInt("logicalSlot", port.logicalSlot());
                p.putBoolean("active", port.active());
                ports.add(p);
            }
            item.putParcelableArrayList("ports", ports);
            encoded.add(item);
        }
        result.putParcelableArrayList("cards", encoded);
        return result;
    }

    static List<Card> decode(Bundle data) {
        if (data == null || data.getInt("schema", -1) != 1) throw new IllegalArgumentException("Invalid inventory");
        ArrayList<Bundle> cards = data.getParcelableArrayList("cards", Bundle.class);
        if (cards == null) throw new IllegalArgumentException("Missing cards");
        List<Card> result = new ArrayList<>();
        for (Bundle item : cards) {
            if (item == null || !item.containsKey("removable")) throw new IllegalArgumentException("Invalid card");
            String eid = item.getString("eid");
            if (!"".equals(eid)) Selection.fingerprint(eid);
            ArrayList<Bundle> rawPorts = item.getParcelableArrayList("ports", Bundle.class);
            if (rawPorts == null) throw new IllegalArgumentException("Missing ports");
            List<Selection.Port> ports = new ArrayList<>();
            for (Bundle p : rawPorts) {
                if (p == null || !p.containsKey("active")) throw new IllegalArgumentException("Invalid port");
                ports.add(new Selection.Port(p.getInt("index", -1), p.getInt("logicalSlot", -1), p.getBoolean("active")));
            }
            result.add(new Card(eid, item.getInt("cardId", -1), item.getInt("slot", -1),
                    item.getBoolean("removable"), List.copyOf(ports)));
        }
        return List.copyOf(result);
    }

    private CardInventory() { }
}
