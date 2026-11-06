package com.lixingchi.flexibleesimtransfer;

import static org.junit.Assert.*;
import static com.lixingchi.flexibleesimtransfer.SelectionTest.*;
import java.util.List;
import org.junit.Test;

public class RoutingSessionTest {
    @Test public void initiallyOffAndCanEnable() {
        RoutingSession session = new RoutingSession();
        assertFalse(session.enabled("", ""));
        assertTrue(session.enabled(HASH, ""));
        Selection.Target target = Selection.resolve(HASH, "", List.of(EXTERNAL));
        assertEquals(target, session.pin(target));
        assertEquals(target, session.pin(target));
    }
    @Test public void changingAnySettingNeedsRestartEvenIfChangedBack() {
        for (String change : List.of("", INTERNAL.fingerprint())) {
            RoutingSession session = new RoutingSession();
            session.enabled(HASH, "");
            assertThrows(Selection.Blocked.class, () -> session.enabled(change, ""));
            assertThrows(Selection.Blocked.class, () -> session.enabled(HASH, ""));
        }
        RoutingSession session = new RoutingSession();
        session.enabled(HASH, "");
        assertThrows(Selection.Blocked.class, () -> session.enabled(HASH, "0"));
    }
    @Test public void cannotMoveAnInFlightTarget() {
        RoutingSession session = new RoutingSession();
        session.enabled(HASH, "");
        Selection.Target original = Selection.resolve(HASH, "", List.of(EXTERNAL));
        session.pin(original);
        assertThrows(Selection.Blocked.class, () -> session.pin(
                new Selection.Target(HASH, 18, 2, 0, 1)));
        assertThrows(Selection.Blocked.class, () -> session.pin(original));
    }
    @Test public void cardRemovalLatchesFailureUntilProcessRestart() {
        RoutingSession session = new RoutingSession();
        session.enabled(HASH, "");
        Selection.Target target = Selection.resolve(HASH, "", List.of(EXTERNAL));
        session.pin(target);
        session.unavailable();
        assertThrows(Selection.Blocked.class, () -> session.pin(target));
        assertThrows(Selection.Blocked.class, () -> session.enabled(HASH, ""));
    }
}
