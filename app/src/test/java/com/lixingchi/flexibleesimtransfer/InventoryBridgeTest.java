package com.lixingchi.flexibleesimtransfer;

import static org.junit.Assert.*;
import static com.lixingchi.flexibleesimtransfer.CardInventoryTest.CARD;
import static com.lixingchi.flexibleesimtransfer.SelectionTest.*;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.telephony.TelephonyManager;
import android.telephony.UiccCardInfo;
import android.telephony.UiccPortInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;

public class InventoryBridgeTest {
    private Context context;
    private Handler handler;
    private final List<InventoryBridge.Result> results = new ArrayList<>();

    @Before public void reset() {
        context = new Context();
        handler = new Handler();
        results.clear();
        HookHarness.preferences.clear();
        HookHarness.rejectHook = null;
    }

    @Test public void requestsArePackageRestrictedAndReceiverRequiresTheSignaturePermission() {
        List<String> events = new ArrayList<>();
        AtomicInteger reads = new AtomicInteger();
        InventoryBridge.register(context, () -> { reads.incrementAndGet(); return List.of(CARD); }, Runnable::run, events::add);
        assertEquals(InventoryBridge.PERMISSION, context.senderPermission);
        assertEquals(Context.RECEIVER_EXPORTED, context.receiverFlags);
        assertEquals(InventoryBridge.ACTION, context.filter.action);
        new InventoryBridge.Query(handler).start(context, results::add);
        assertEquals(InventoryBridge.HOST, context.lastIntent.getPackage());
        assertEquals(InventoryBridge.ACTION, context.lastIntent.getAction());
        assertEquals(List.of(new InventoryBridge.Result(InventoryBridge.OK, List.of(CARD))), results);
        assertTrue(handler.delayed.isEmpty());
        assertEquals(List.of("READY"), events);
        context.senderAuthorized = false;
        new InventoryBridge.Query(handler).start(context, results::add);
        assertEquals(InventoryBridge.UNAVAILABLE, results.get(1).status());
        assertNull(results.get(1).cards());
        assertEquals(1, reads.get());
        // The manifest declares this permission signature-only; Android enforces it, not this double.
        assertFalse(events.toString().contains(EID));
    }

    @Test public void emptyFailedAndMissingReaderAreDifferentResults() {
        new InventoryBridge.Query(handler).start(context, results::add);
        assertEquals(InventoryBridge.UNAVAILABLE, results.get(0).status());
        InventoryBridge.register(context, List::of, Runnable::run, ignored -> { });
        new InventoryBridge.Query(handler).start(context, results::add);
        assertEquals(InventoryBridge.OK, results.get(1).status());
        assertEquals(List.of(), results.get(1).cards());
        InventoryBridge.register(context, () -> { throw new SecurityException(EID); }, Runnable::run, ignored -> { });
        new InventoryBridge.Query(handler).start(context, results::add);
        assertEquals(InventoryBridge.FAILED, results.get(2).status());
        assertNull(results.get(2).cards());
        context.rejectSend = true;
        new InventoryBridge.Query(handler).start(context, results::add);
        assertEquals(InventoryBridge.UNAVAILABLE, results.get(3).status());
        assertTrue(handler.delayed.isEmpty());
    }

    @Test public void malformedRepliesAndRejectedWorkersDoNotReturnCards() {
        InventoryBridge.register(context, List::of, work -> { throw new IllegalStateException(); }, ignored -> { });
        new InventoryBridge.Query(handler).start(context, results::add);
        assertEquals(InventoryBridge.FAILED, results.get(0).status());
        // A claimed success with no inventory must not be confused with an empty inventory.
        context.receiver = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, android.content.Intent intent) {
                pending.setResult(InventoryBridge.OK, null, null);
            }
        };
        new InventoryBridge.Query(handler).start(context, results::add);
        assertEquals(InventoryBridge.INVALID, results.get(1).status());
    }

    @Test public void timeoutOrCancellationDiscardsLateReplyWithoutReplacingNewerScan() {
        InventoryBridge.register(context, () -> List.of(CARD), Runnable::run, ignored -> { });
        context.holdResult = true;
        InventoryBridge.Query old = new InventoryBridge.Query(handler);
        old.start(context, results::add);
        Runnable late = context.deliverResult;
        handler.elapse();
        assertEquals(InventoryBridge.TIMEOUT, results.get(0).status());
        context.holdResult = false;
        new InventoryBridge.Query(handler).start(context, results::add);
        late.run();
        assertEquals(2, results.size());
        assertEquals(InventoryBridge.OK, results.get(1).status());
        context.holdResult = true;
        InventoryBridge.Query cancelled = new InventoryBridge.Query(handler);
        cancelled.start(context, results::add);
        cancelled.cancel();
        context.deliverResult.run();
        handler.elapse();
        assertEquals(2, results.size());
        assertTrue(handler.delayed.isEmpty());
    }

    @Test public void discoveryBypassesRoutingAndKeepsTheOriginalOrderEvenWithStaleSettings() throws Exception {
        for (boolean enabled : List.of(false, true)) {
            if (enabled) HookHarness.preferences.put(Selection.KEY, HASH);
            HookHarness.load(InventoryBridge.HOST, 10001);
            context = new Context();
            context.telephony = new TelephonyManager();
            context.telephony.cards = List.of(
                    new UiccCardInfo(SOURCE_EID, 4, 0, true, false, List.of(new UiccPortInfo(0, 0, true))),
                    new UiccCardInfo(EID, 17, 1, true, true, List.of(new UiccPortInfo(0, 1, true))));
            new Application().attach(context);
            new Application().attach(context);
            assertEquals(1, context.registrations);
            if (enabled) {
                assertEquals(17, context.telephony.getUiccCardsInfo().get(0).getCardId());
                HookHarness.preferences.put(Selection.KEY, "invalid-setting");
            }
            CompletableFuture<InventoryBridge.Result> response = new CompletableFuture<>();
            new InventoryBridge.Query(handler).start(context, response::complete);
            InventoryBridge.Result result = response.get(5, TimeUnit.SECONDS);
            assertEquals(InventoryBridge.OK, result.status());
            assertEquals(List.of(SOURCE_EID, EID), result.cards().stream().map(CardInventory.Card::eid).toList());
            assertEquals(enabled ? "invalid-setting" : null, HookHarness.preferences.get(Selection.KEY));
            String logs = HookHarness.logs.toString();
            for (String sensitive : List.of(EID, SOURCE_EID, HASH)) assertFalse(logs.contains(sensitive));
        }
    }

    @Test public void discoveryFailureDoesNotMarkRoutingIncompleteOrRegisterInGms() throws Exception {
        HookHarness.load(InventoryBridge.HOST, 10001);
        context.rejectRegistration = true;
        new Application().attach(context);
        assertTrue(HookHarness.logs.toString().contains("inventory_bridge: UNAVAILABLE"));
        assertTrue(HookHarness.logs.toString().contains("READY:LPA"));
        assertFalse(HookHarness.logs.toString().contains("INCOMPLETE"));
        HookHarness.load("com.google.android.gms", 10001);
        context = new Context();
        new Application().attach(context);
        assertNull(context.receiver);
    }
}
