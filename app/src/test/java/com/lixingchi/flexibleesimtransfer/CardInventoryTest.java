package com.lixingchi.flexibleesimtransfer;

import static org.junit.Assert.*;
import static com.lixingchi.flexibleesimtransfer.SelectionTest.*;

import android.os.Bundle;
import android.telephony.UiccCardInfo;
import android.telephony.UiccPortInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class CardInventoryTest {
    static final CardInventory.Card CARD = new CardInventory.Card(EID, 17, 1, true, List.of(
            new Selection.Port(0, 1, true), new Selection.Port(3, -1, false), new Selection.Port(7, 2, true)));

    @Test public void readsRealEidsRemovabilityAndNonConsecutivePortsWithoutChangingInventory() {
        List<UiccCardInfo> inventory = List.of(
                new UiccCardInfo(SOURCE_EID, 4, 0, true, false, List.of(new UiccPortInfo(2, 0, true))),
                new UiccCardInfo(EID, 17, 1, true, true, List.of(
                        new UiccPortInfo(0, 1, true), new UiccPortInfo(3, -1, false), new UiccPortInfo(7, 2, true))),
                new UiccCardInfo(null, 99, 2, false, true, List.of()));
        List<CardInventory.Card> cards = CardInventory.fromTelephony(inventory);
        assertEquals(2, cards.size());
        assertEquals(SOURCE_EID, cards.get(0).eid());
        assertFalse(cards.get(0).removable());
        assertEquals(CARD, cards.get(1));
        assertEquals(4, inventory.get(0).getCardId());
        assertEquals(7, CardInventory.choose(cards, CARD, CARD.ports().get(2)).port());
        assertThrows(Selection.Blocked.class, () -> CardInventory.choose(cards, CARD, CARD.ports().get(1)));
        assertFalse(cards.toString().contains(EID));
    }

    @Test public void unreadableEidsAndEmptyInventoryRemainVisibleButCannotBeSelected() {
        for (String value : new String[]{null, "", "0".repeat(32), " " + EID, "redacted"}) {
            List<CardInventory.Card> cards = CardInventory.fromTelephony(List.of(
                    new UiccCardInfo(value, 17, 1, true, true, List.of(new UiccPortInfo(0, 1, true)))));
            assertEquals("", cards.get(0).eid());
            assertThrows(Selection.Blocked.class,
                    () -> CardInventory.choose(cards, cards.get(0), cards.get(0).ports().get(0)));
        }
        assertEquals(List.of(), CardInventory.fromTelephony(List.of()));
        assertThrows(Selection.Blocked.class, () -> CardInventory.fromTelephony(null));
    }

    @Test public void invalidAddressesDuplicateEidsAndInactivePortsCannotBeChosen() {
        for (CardInventory.Card invalid : List.of(
                new CardInventory.Card(EID, -1, 1, true, PORTS),
                new CardInventory.Card(EID, 17, -1, true, PORTS),
                new CardInventory.Card(EID, 17, 1, true, List.of(new Selection.Port(0, -1, true))),
                new CardInventory.Card(EID, 17, 1, true, List.of(new Selection.Port(-1, 1, true))),
                new CardInventory.Card(EID, 17, 1, true, List.of(new Selection.Port(0, 1, false))))) {
            assertThrows(Selection.Blocked.class,
                    () -> CardInventory.choose(List.of(invalid), invalid, invalid.ports().get(0)));
        }
        assertThrows(Selection.Blocked.class,
                () -> CardInventory.choose(List.of(CARD, CARD), CARD, CARD.ports().get(0)));
        assertThrows(Selection.Blocked.class,
                () -> CardInventory.choose(List.of(), CARD, CARD.ports().get(0)));
        assertThrows(Selection.Blocked.class,
                () -> CardInventory.choose(List.of(CARD), CARD, new Selection.Port(9, 9, true)));
        CardInventory.Card duplicateIndex = new CardInventory.Card(EID, 17, 1, true,
                List.of(new Selection.Port(0, 1, false), new Selection.Port(0, 2, true)));
        assertThrows(Selection.Blocked.class, () -> CardInventory.choose(
                List.of(duplicateIndex), duplicateIndex, duplicateIndex.ports().get(0)));
    }

    @Test public void manualInputIsExactAndKnownPortsAreValidatedButUnknownEidsAllowFallback() {
        List<CardInventory.Card> cards = List.of(CARD);
        assertTrue(CardInventory.verifyManual(cards, EID, "007"));
        assertThrows(Selection.Blocked.class, () -> CardInventory.verifyManual(cards, EID, ""));
        assertThrows(Selection.Blocked.class, () -> CardInventory.verifyManual(cards, EID, "3"));
        assertThrows(Selection.Blocked.class, () -> CardInventory.verifyManual(cards, EID, "4"));
        assertThrows(IllegalArgumentException.class, () -> CardInventory.verifyManual(cards, " " + EID, "0"));
        assertThrows(IllegalArgumentException.class, () -> CardInventory.verifyManual(null, EID, " 0"));
        assertThrows(IllegalArgumentException.class, () -> CardInventory.verifyManual(null, EID, "2147483648"));
        assertFalse(CardInventory.verifyManual(null, EID, ""));
        assertFalse(CardInventory.verifyManual(cards, SOURCE_EID, "0"));
        CardInventory.Card single = new CardInventory.Card(EID, 17, 1, true, PORTS);
        assertTrue(CardInventory.verifyManual(List.of(single), EID, ""));
    }

    @Test public void roundTripUsesOnlyExplicitInventoryFieldsNotFullTelephonyObjects() {
        Bundle data = CardInventory.encode(List.of(CARD));
        assertEquals(List.of(CARD), CardInventory.decode(data));
        assertEquals(List.of(), CardInventory.decode(CardInventory.encode(List.of())));
        assertEquals(Set.of("schema", "cards"), data.keySet());
        Bundle card = data.getParcelableArrayList("cards", Bundle.class).get(0);
        assertEquals(Set.of("eid", "cardId", "slot", "removable", "ports"), card.keySet());
        for (Bundle port : card.getParcelableArrayList("ports", Bundle.class))
            assertEquals(Set.of("index", "logicalSlot", "active"), port.keySet());
    }

    @Test public void malformedResponseIsRejectedOrHasUnselectableAddresses() {
        assertThrows(IllegalArgumentException.class, () -> CardInventory.decode(null));
        assertThrows(IllegalArgumentException.class, () -> CardInventory.decode(new Bundle()));
        Bundle data = CardInventory.encode(List.of(CARD));
        data.putInt("schema", 2);
        assertThrows(IllegalArgumentException.class, () -> CardInventory.decode(data));
        data.putInt("schema", 1);
        ArrayList<Bundle> cards = data.getParcelableArrayList("cards", Bundle.class);
        Bundle card = cards.get(0);
        card.putString("eid", EID + " ");
        assertThrows(IllegalArgumentException.class, () -> CardInventory.decode(data));
        card.putString("eid", EID);
        card.remove("slot");
        CardInventory.Card decoded = CardInventory.decode(data).get(0);
        assertThrows(Selection.Blocked.class,
                () -> CardInventory.choose(List.of(decoded), decoded, decoded.ports().get(0)));
        card.remove("ports");
        assertThrows(IllegalArgumentException.class, () -> CardInventory.decode(data));
        cards.set(0, null);
        assertThrows(IllegalArgumentException.class, () -> CardInventory.decode(data));
    }
}
