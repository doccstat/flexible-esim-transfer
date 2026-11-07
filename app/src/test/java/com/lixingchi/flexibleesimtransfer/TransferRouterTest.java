package com.lixingchi.flexibleesimtransfer;

import static org.junit.Assert.*;
import static com.lixingchi.flexibleesimtransfer.SelectionTest.*;
import android.app.*;
import android.content.Context;
import android.telephony.*;
import android.telephony.euicc.*;
import com.android.euicc.service.EuiccServiceImpl;
import com.google.android.euicc.config.SimSlotMapping;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;

public class TransferRouterTest {
    private TelephonyManager tm;
    private UiccCardInfo builtIn;
    private UiccCardInfo removable;

    @Before public void reset() {
        HookHarness.preferences.clear();
        HookHarness.rejectHook = null;
        HookHarness.preferences.put(Selection.KEY, HASH);
        HookHarness.preferences.put(Selection.PORT, "");
        builtIn = new UiccCardInfo(SOURCE_EID, 4, 0, true, List.of(new UiccPortInfo(0, 0, true)));
        removable = new UiccCardInfo(EID, 17, 1, true, true, List.of(new UiccPortInfo(0, 1, true)));
        tm = new TelephonyManager();
        tm.cards = List.of(builtIn, removable);
        EuiccManager.eids.clear();
        EuiccManager.eids.put(4, SOURCE_EID);
        EuiccManager.eids.put(17, EID);
        EuiccManager.originalCalls = 0;
        EuiccManager.downloadedCard = -1;
        EuiccManager.switchedCard = -1;
        EuiccManager.continuedCard = -1;
        EuiccManager.failure = null;
    }
    private void load(String packageName) throws Exception {
        HookHarness.load(packageName, 10001);
        Context context = new Context();
        context.telephony = tm;
        context.subscriptions.subscriptions = List.of(new SubscriptionInfo(501, 17), new SubscriptionInfo(80, 4));
        new Application().attach(context);
        assertTrue(HookHarness.logs.toString(), HookHarness.logs.stream().anyMatch(s -> s.contains("READY:")));
    }
    private FixtureSlots slots() {
        FixtureSlots slots = new FixtureSlots(); slots.telephony = tm; return slots;
    }

    @Test public void entitlementAndDownloadUseTheSameRealCard() throws Exception {
        load("com.google.android.gms");
        // GMS selects the first eUICC before sending destination metadata to the source/carrier.
        List<UiccCardInfo> ordered = tm.getUiccCardsInfo();
        String authorizedEid = ordered.stream().filter(UiccCardInfo::isEuicc).findFirst().orElseThrow().getEid();
        assertEquals(EID, authorizedEid);
        assertEquals(EID, new EuiccManager(4).getEid());
        assertSame(builtIn, ordered.get(1));
        assertSame(builtIn, tm.cards.get(0));
        assertEquals(SOURCE_EID, ordered.stream().filter(c -> c.getCardId() == 4).findFirst().orElseThrow().getEid());

        load("com.google.android.euicc");
        EuiccManager cachedDefault = new EuiccManager(4);
        PendingIntent callback = new PendingIntent();
        cachedDefault.downloadSubscription(new DownloadableSubscription(), true, callback);
        assertEquals(17, EuiccManager.downloadedCard);
        assertEquals(4, cachedDefault.card);
        int slot = slots().getPreferredEuiccSlotId(new UiccSlotInfo[0]);
        assertEquals(1, slot);
        assertEquals(authorizedEid, slots().getEidForSlot(-1));
        assertEquals(List.of(0), new SimSlotMapping().getEsimSlotIds());
        assertEquals(List.of(1), new SimSlotMapping().getPsimSlotIds());
        assertEquals(0, new EuiccServiceImpl().onDownloadSubscription(slot, 0,
                new DownloadableSubscription(), true, false, null).result);
        EuiccCardManager card = new EuiccCardManager();
        byte[] bound = {1, 2, 3};
        AtomicInteger completed = new AtomicInteger();
        card.loadBoundProfilePackage(authorizedEid, bound, Runnable::run, (code, value) -> {
            assertEquals(0, code); assertSame(bound, value); completed.incrementAndGet();
        });
        assertEquals(1, completed.get());
        assertEquals(1, card.calls);
        assertEquals(EID, card.card);
        assertSame(bound, card.payload);
    }
    @Test public void routingPreservesThePhysicalTrayAndNativeHardwareLists() throws Exception {
        for (String eid : List.of(EID, SOURCE_EID)) {
            HookHarness.preferences.put(Selection.KEY, Selection.fingerprint(eid));
            load("com.google.android.euicc");
            assertEquals(eid, new EuiccManager(4).getEid());
            SimSlotMapping mapping = new SimSlotMapping();
            // An empty tray list sends native Settings to download-only instead of the transfer menu.
            assertFalse(mapping.getPsimSlotIds().isEmpty());
            assertSame(mapping.removable, mapping.getPsimSlotIds());
            assertSame(mapping.embedded, mapping.getEsimSlotIds());
            mapping.removable = List.of(1, 4);
            mapping.embedded = List.of(0, 7);
            assertSame(mapping.removable, mapping.getPsimSlotIds());
            assertSame(mapping.embedded, mapping.getEsimSlotIds());
            HookHarness.preferences.put(Selection.KEY, "");
            assertSlotsUnavailable(slots());
            assertSame(mapping.removable, mapping.getPsimSlotIds());
            assertSame(mapping.embedded, mapping.getEsimSlotIds());
        }
    }
    @Test public void routingDoesNotInventAPhysicalTrayOnEsimOnlyHardware() throws Exception {
        HookHarness.preferences.put(Selection.KEY, Selection.fingerprint(SOURCE_EID));
        tm.cards = List.of(builtIn);
        load("com.google.android.euicc");
        assertEquals(SOURCE_EID, new EuiccManager(4).getEid());
        SimSlotMapping mapping = new SimSlotMapping();
        mapping.removable = List.of();
        assertSame(mapping.removable, mapping.getPsimSlotIds());
        assertSame(mapping.embedded, mapping.getEsimSlotIds());
    }
    @Test public void explicitSourceQueriesRemainReal() throws Exception {
        load("com.google.android.euicc");
        EuiccManager original = new EuiccManager(4);
        assertEquals(SOURCE_EID, original.createForCardId(4).getEid());
        assertEquals(SOURCE_EID, slots().getEidForSlot(0));
        assertEquals(EID, original.getEid());
    }
    @Test public void postDownloadEnableBindsByRealSubscriptionNotByDefaultManager() throws Exception {
        load("com.google.android.euicc");
        EuiccManager cached = new EuiccManager(4);
        cached.switchToSubscription(501, new PendingIntent());
        assertEquals(17, EuiccManager.switchedCard);
        assertEquals(501, EuiccManager.switchedSubscription);
        cached.switchToSubscription(80, new PendingIntent());
        assertEquals(4, EuiccManager.switchedCard);
        cached.switchToSubscription(-1, new PendingIntent());
        assertEquals(4, EuiccManager.switchedCard);
        assertEquals(-1, EuiccManager.switchedSubscription);
    }
    @Test public void resolutionsStayBoundToTheirOriginalCard() throws Exception {
        load("com.google.android.euicc");
        EuiccManager cached = new EuiccManager(4);
        cached.continueOperation(new android.content.Intent()
                .putExtra("android.service.euicc.extra.RESOLUTION_CARD_ID", 17), null);
        assertEquals(17, EuiccManager.continuedCard);
        cached.continueOperation(new android.content.Intent()
                .putExtra("android.service.euicc.extra.RESOLUTION_CARD_ID", 4), null);
        assertEquals(4, EuiccManager.continuedCard);
    }
    @Test public void defaultOffIsAnExactPassThrough() throws Exception {
        HookHarness.preferences.clear();
        load("com.google.android.euicc");
        assertSame(tm.cards, tm.getUiccCardsInfo());
        assertEquals(SOURCE_EID, new EuiccManager(4).getEid());
        assertEquals(0, slots().getFirstActiveEuiccSlotId());
        new EuiccManager(4).downloadSubscription(new DownloadableSubscription(), false, new PendingIntent());
        assertEquals(4, EuiccManager.downloadedCard);
        assertEquals(0, new EuiccServiceImpl().onDownloadSubscription(0, 0, null, false, false, null).result);
    }
    @Test public void originalExceptionsAreNotRetriedOrReportedAsSuccess() throws Exception {
        load("com.google.android.euicc");
        IllegalStateException failure = new IllegalStateException("stock failure");
        EuiccManager.failure = failure;
        PendingIntent callback = new PendingIntent();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> new EuiccManager(4)
                .downloadSubscription(new DownloadableSubscription(), true, callback)));
        assertEquals(1, EuiccManager.originalCalls);
        assertEquals(0, callback.calls);
    }
    @Test public void removalBlocksRatherThanDownloadingToBuiltIn() throws Exception {
        load("com.google.android.euicc");
        assertEquals(EID, new EuiccManager(4).getEid());
        tm.cards = List.of(builtIn);
        PendingIntent callback = new PendingIntent();
        new EuiccManager(4).downloadSubscription(new DownloadableSubscription(), true, callback);
        assertEquals(1, callback.calls);
        assertEquals(2, callback.result);
        assertEquals(-1, EuiccManager.downloadedCard);
        tm.cards = List.of(builtIn, removable);
        assertThrows(Selection.Blocked.class, () -> new EuiccManager(4).getEid());
    }
    @Test public void wrongServiceSlotOrPortNeverReachesOriginal() throws Exception {
        load("com.google.android.euicc");
        EuiccServiceImpl service = new EuiccServiceImpl();
        assertEquals(1, service.onDownloadSubscription(0, 0, null, true, false, null).result);
        assertEquals(1, service.onDownloadSubscription(1, 9, null, true, false, null).result);
        assertEquals(1, service.onGetDownloadableSubscriptionMetadata(0, null, false).result);
        assertEquals(0, service.calls);
        service.result = 27;
        assertEquals(27, service.onDownloadSubscription(1, 0, null, true, false, null).result);
        assertEquals(1, service.calls);
        assertTrue(HookHarness.logs.toString().contains("service_result: download=27"));
    }
    @Test public void cryptographicOperationsNeverSubstituteAnEidOrFakeSuccess() throws Exception {
        load("com.google.android.euicc");
        EuiccCardManager card = new EuiccCardManager();
        AtomicInteger callback = new AtomicInteger();
        card.authenticateServer(SOURCE_EID, "not-rewritten", null, null, null, null, Runnable::run,
                (code, value) -> { assertEquals(-1, code); assertNull(value); callback.incrementAndGet(); });
        assertEquals(0, card.calls);
        assertEquals(1, callback.get());
        card.result = -4;
        card.requestEuiccChallenge(EID, Runnable::run, (code, value) -> assertEquals(-4, code));
        assertEquals(1, card.calls);
    }
    @Test public void configurationChangeBlocksUntilProcessesRestart() throws Exception {
        load("com.google.android.euicc");
        new EuiccManager(4).getEid();
        HookHarness.preferences.put(Selection.KEY, "");
        EuiccServiceImpl service = new EuiccServiceImpl();
        assertEquals(1, service.onDownloadSubscription(1, 0, null, false, false, null).result);
        assertEquals(0, service.calls);
        load("com.google.android.euicc");
        assertEquals(SOURCE_EID, new EuiccManager(4).getEid());
    }
    private void assertSlotsUnavailable(FixtureSlots slots) {
        assertEquals(Map.entry(-1, -1), slots.getFirstActiveEuiccSlotIdAndPort());
        assertEquals(-1, slots.getActiveEuiccSlotId());
        assertEquals(-1, slots.getFirstActiveEuiccSlotId());
        assertEquals(-1, slots.getFirstActiveEuiccLogicalSlotId());
        assertEquals(-1, slots.getPreferredEuiccSlotId(new UiccSlotInfo[0]));
    }
    @Test public void changedDestinationDoesNotCrashSlotPollingOrUnblockDownloads() throws Exception {
        load("com.google.android.euicc");
        FixtureSlots slots = slots();
        assertEquals(Map.entry(1, 0), slots.getFirstActiveEuiccSlotIdAndPort());
        HookHarness.preferences.put(Selection.KEY, Selection.fingerprint(SOURCE_EID));
        assertSlotsUnavailable(slots);
        assertTrue(HookHarness.logs.toString().contains("blocked: SETTINGS_CHANGED_RESTART_REQUIRED"));
        EuiccServiceImpl service = new EuiccServiceImpl();
        for (int slot : List.of(0, 1))
            assertEquals(1, service.onDownloadSubscription(slot, 0, null, false, false, null).result);
        assertEquals(0, service.calls);
        EuiccCardManager card = new EuiccCardManager();
        AtomicInteger failures = new AtomicInteger();
        for (String eid : List.of(EID, SOURCE_EID))
            card.requestEuiccChallenge(eid, Runnable::run, (code, value) -> {
                assertEquals(-1, code); assertNull(value); failures.incrementAndGet();
            });
        assertEquals(2, failures.get());
        assertEquals(0, card.calls);
        assertEquals(SOURCE_EID, new EuiccManager(4).createForCardId(4).getEid());
        assertThrows(Selection.Blocked.class, () -> new EuiccManager(4).getEid());
        load("com.google.android.euicc");
        assertEquals(Map.entry(0, 0), slots.getFirstActiveEuiccSlotIdAndPort());
    }
    @Test public void removalKeepsSlotPollingUnavailableEvenAfterReinsertion() throws Exception {
        load("com.google.android.euicc");
        FixtureSlots slots = slots();
        assertEquals(1, slots.getFirstActiveEuiccSlotId());
        tm.cards = List.of(builtIn);
        assertSlotsUnavailable(slots);
        assertTrue(HookHarness.logs.toString().contains("blocked: CARD_NOT_FOUND"));
        tm.cards = List.of(builtIn, removable);
        assertSlotsUnavailable(slots);
        PendingIntent callback = new PendingIntent();
        new EuiccManager(4).downloadSubscription(new DownloadableSubscription(), true, callback);
        assertEquals(2, callback.result);
        assertEquals(-1, EuiccManager.downloadedCard);
    }
    @Test public void slotPollingDoesNotSuppressNativeExceptionsWhenRoutingIsOff() throws Exception {
        HookHarness.preferences.clear();
        load("com.google.android.euicc");
        FixtureSlots slots = slots();
        IllegalStateException failure = new IllegalStateException("native slot failure");
        slots.failure = failure;
        assertSame(failure, assertThrows(IllegalStateException.class, slots::getFirstActiveEuiccSlotIdAndPort));
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> slots.getPreferredEuiccSlotId(new UiccSlotInfo[0])));
    }
    @Test public void incompleteHookSetupKeepsFinalGuardsClosed() throws Exception {
        HookHarness.rejectHook = "getPreferredEuiccSlotId";
        TransferRouter module = HookHarness.load("com.google.android.euicc", 10001);
        HookHarness.notifyReady(module, "com.google.android.euicc", 10001);
        Context context = new Context(); context.telephony = tm; new Application().attach(context);
        assertTrue(HookHarness.logs.toString().contains("INCOMPLETE"));
        assertFalse(HookHarness.logs.toString().contains("READY:LPA"));
        assertEquals(Map.entry(-1, -1), slots().getFirstActiveEuiccSlotIdAndPort());
        EuiccServiceImpl service = new EuiccServiceImpl();
        assertEquals(1, service.onDownloadSubscription(1, 0, null, false, false, null).result);
        assertEquals(0, service.calls);
    }
    @Test public void wrongPackageAndOtherAndroidUsersAreNotHooked() throws Exception {
        HookHarness.load("com.android.server.telecom", 10001);
        assertTrue(HookHarness.hooks.isEmpty());
        HookHarness.load("com.google.android.gms", 1010001);
        assertTrue(HookHarness.hooks.isEmpty());
    }
    @Test public void loggingNeverLeaksIdentifiersOrProfileMaterial() throws Exception {
        load("com.google.android.euicc");
        new EuiccManager(4).getEid();
        new EuiccManager(4).downloadSubscription(new DownloadableSubscription(), true, new PendingIntent());
        String logs = HookHarness.logs.toString();
        for (String secret : List.of(EID, SOURCE_EID, HASH)) assertFalse(logs.contains(secret));
    }
}
