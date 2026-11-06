package com.lixingchi.flexibleesimtransfer;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class SelectionTest {
    private static final String EID = "89012345678901234567890123456789";
    private static final String HASH = Selection.fingerprint(EID);
    private static final Selection.Card EXTERNAL = new Selection.Card(HASH, 17, 1, true, true, true);
    private static final Selection.Card INTERNAL = new Selection.Card(
            Selection.fingerprint("89012345678901234567890123456780"), 4, 0, true, false, true);

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
            Selection.Result result = Selection.resolve(HASH, cards);
            assertEquals("READY", result.status());
            assertSame(EXTERNAL, result.card());
            assertEquals("selection=READY;cardMatch=true", result.compareCard(17));
            assertEquals("selection=READY;cardMatch=false", result.compareCard(1));
            assertEquals("selection=READY;slotMatch=true", result.compareSlot(1));
            assertEquals("selection=READY;slotMatch=false", result.compareSlot(17));
        }
    }
    @Test public void offNeverSelectsDefault() {
        for (String bad : Arrays.asList(null, "", EID, "0".repeat(63))) {
            assertEquals("OFF", Selection.resolve(bad, List.of(EXTERNAL)).status());
        }
    }
    @Test public void unavailableAndMissingDoNotFallBack() {
        assertEquals("UNAVAILABLE", Selection.resolve(HASH, null).status());
        Selection.Result missing = Selection.resolve(HASH, Arrays.asList(null, INTERNAL));
        assertEquals("NOT_FOUND", missing.status());
        assertNull(missing.card());
        assertFalse(missing.compareSlot(0).endsWith("true"));
    }
    @Test public void duplicatesAreAmbiguousEvenAtSameAddress() {
        assertEquals("AMBIGUOUS", Selection.resolve(HASH, List.of(EXTERNAL, EXTERNAL)).status());
    }
    @Test public void embeddedAndOrdinarySimRejected() {
        for (Selection.Card card : List.of(
                new Selection.Card(HASH, 17, 1, true, false, true),
                new Selection.Card(HASH, 17, 1, false, true, true))) {
            assertEquals("NOT_REMOVABLE_EUICC", Selection.resolve(HASH, List.of(card)).status());
        }
    }
    @Test public void inactiveCardRejected() {
        assertEquals("INACTIVE", Selection.resolve(HASH, List.of(
                new Selection.Card(HASH, 17, 1, true, true, false))).status());
    }
    @Test public void invalidAddressesRejected() {
        for (Selection.Card card : List.of(
                new Selection.Card(HASH, -1, 1, true, true, true),
                new Selection.Card(HASH, 17, -1, true, true, true))) {
            assertEquals("INVALID_ADDRESS", Selection.resolve(HASH, List.of(card)).status());
        }
    }
    @Test public void eidComparisonNeverEchoesIdentifiers() {
        assertEquals("eidMatch=true", Selection.compareEid(HASH, EID));
        assertEquals("eidMatch=false", Selection.compareEid(HASH, "1".repeat(32)));
        assertEquals("eidMatch=UNAVAILABLE", Selection.compareEid(HASH, null));
        assertEquals("selection=OFF", Selection.compareEid("", EID));
        assertFalse(Selection.compareEid(HASH, EID).contains(EID));
        assertFalse(Selection.resolve(HASH, List.of(EXTERNAL)).compareCard(17).contains(HASH));
    }
}
