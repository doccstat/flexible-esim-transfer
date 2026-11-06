package com.lixingchi.flexibleesimtransfer;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class SelectionTest {
    static final String EID = "89012345678901234567890123456789";
    static final String SOURCE_EID = "89012345678901234567890123456780";
    static final String HASH = Selection.fingerprint(EID);
    static final List<Selection.Port> PORTS = List.of(new Selection.Port(0, 1, true));
    static final Selection.Card EXTERNAL = new Selection.Card(HASH, 17, 1, true, PORTS);
    static final Selection.Card INTERNAL = new Selection.Card(
            Selection.fingerprint(SOURCE_EID), 4, 0, true, List.of(new Selection.Port(0, 0, true)));

    @Test public void exactInputOnly() {
        assertEquals(64, HASH.length());
        assertTrue(Selection.configured(HASH));
        for (String bad : Arrays.asList(null, "", EID + " ", " " + EID,
                EID.substring(1), "0".repeat(32), "１".repeat(32), "a".repeat(32))) {
            assertThrows(IllegalArgumentException.class, () -> Selection.fingerprint(bad));
        }
    }
    @Test public void realCardAddressNotArrayPositionOrDefault() {
        for (List<Selection.Card> cards : List.of(List.of(INTERNAL, EXTERNAL), List.of(EXTERNAL, INTERNAL))) {
            Selection.Target target = Selection.resolve(HASH, "", cards);
            assertEquals(17, target.cardId());
            target.requireAddress(1, 0);
            target.requireEid(EID);
            assertThrows(Selection.Blocked.class, () -> target.requireAddress(17, 0));
            assertThrows(Selection.Blocked.class, () -> target.requireAddress(1, 1));
            assertThrows(Selection.Blocked.class, () -> target.requireEid(SOURCE_EID));
        }
    }
    @Test public void invalidConfigurationNeverSelectsDefault() {
        for (String bad : Arrays.asList(null, "", EID, "0".repeat(63))) {
            blocked("INVALID_SELECTION", bad, "", List.of(EXTERNAL));
        }
    }
    @Test public void unavailableAndMissingDoNotFallBack() {
        blocked("INVENTORY_UNAVAILABLE", HASH, "", null);
        blocked("CARD_NOT_FOUND", HASH, "", Arrays.asList(null, INTERNAL));
    }
    @Test public void duplicateCardIsAmbiguousEvenAtSameAddress() {
        blocked("AMBIGUOUS_CARD", HASH, "", List.of(EXTERNAL, EXTERNAL));
    }
    @Test public void eitherBuiltInOrRemovableCanBeExplicitlySelected() {
        assertEquals(4, Selection.resolve(INTERNAL.fingerprint(), "", List.of(INTERNAL, EXTERNAL)).cardId());
        assertEquals(17, Selection.resolve(HASH, "", List.of(INTERNAL, EXTERNAL)).cardId());
        blocked("NOT_EUICC", HASH, "", List.of(new Selection.Card(HASH, 17, 1, false, PORTS)));
    }
    @Test public void inactiveAndInvalidAddressesRejected() {
        blocked("PORT_INACTIVE", HASH, "", List.of(new Selection.Card(HASH, 17, 1, true,
                List.of(new Selection.Port(0, 1, false)))));
        for (Selection.Card card : List.of(new Selection.Card(HASH, -1, 1, true, PORTS),
                new Selection.Card(HASH, 17, -1, true, PORTS)))
            blocked("INVALID_ADDRESS", HASH, "", List.of(card));
        blocked("INVALID_PORT_ADDRESS", HASH, "", List.of(new Selection.Card(HASH, 17, 1, true,
                List.of(new Selection.Port(0, -1, true)))));
    }
    @Test public void multipleActivePortsRequireExplicitChoice() {
        List<Selection.Card> cards = List.of(new Selection.Card(HASH, 17, 1, true,
                List.of(new Selection.Port(0, 1, true), new Selection.Port(2, 3, true))));
        blocked("SELECT_PORT", HASH, "", cards);
        assertEquals(2, Selection.resolve(HASH, "2", cards).port());
        assertEquals(3, Selection.resolve(HASH, "2", cards).logicalSlot());
        blocked("PORT_INACTIVE", HASH, "1", cards);
    }
    @Test public void portInputIsNotSilentlyRewritten() {
        assertEquals(-1, Selection.parsePort(""));
        assertEquals(2, Selection.parsePort("002"));
        for (String bad : Arrays.asList(null, "-1", " 0", "0 ", "+1", "１", "999999999999"))
            assertThrows(IllegalArgumentException.class, () -> Selection.parsePort(bad));
    }
    @Test public void noIdentifiersInFailureMessages() {
        Selection.Target target = Selection.resolve(HASH, "", List.of(EXTERNAL));
        String error = assertThrows(Selection.Blocked.class, () -> target.requireEid(SOURCE_EID)).getMessage();
        assertEquals("EID_MISMATCH", error);
        assertFalse(Selection.matches(HASH, null));
        assertFalse(Selection.matches(HASH, SOURCE_EID));
        assertTrue(Selection.matches(HASH, EID));
    }
    private static void blocked(String expected, String selected, String port, List<Selection.Card> cards) {
        assertEquals(expected, assertThrows(Selection.Blocked.class,
                () -> Selection.resolve(selected, port, cards)).getMessage());
    }
}
