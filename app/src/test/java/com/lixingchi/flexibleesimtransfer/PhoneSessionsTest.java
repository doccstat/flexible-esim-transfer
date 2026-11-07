package com.lixingchi.flexibleesimtransfer;

import static org.junit.Assert.*;
import static com.lixingchi.flexibleesimtransfer.SelectionTest.*;
import android.content.Context;
import android.os.Message;
import android.telephony.*;
import com.android.internal.telephony.euicc.*;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.uicc.euicc.EuiccPort;
import com.android.internal.telephony.uicc.euicc.apdu.ApduSender;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;

public class PhoneSessionsTest {
    private Context context;
    private EuiccConnector connector;
    private EuiccSession nativeSession;
    private ApduSender sender;
    private int unavailable;
    @Before public void setup() throws Exception {
        HookHarness.preferences.clear(); HookHarness.rejectHook = null; HookHarness.rejectDeoptimization = null;
        HookHarness.preferences.put(Selection.KEY, HASH);
        HookHarness.preferences.put(Selection.PORT, "0");
        HookHarness.preferences.put(Selection.PRESERVE_SESSION, true);
        unavailable = 0;
        EuiccSession.instance = null;
        context = new Context(); context.telephony = new TelephonyManager();
        context.telephony.cards = List.of(new UiccCardInfo(EID, 17, 1, true, true,
                List.of(new UiccPortInfo(0, 0, true))));
        connector = new EuiccConnector(context);
        nativeSession = EuiccSession.get(context);
        sender = new ApduSender();
        UiccController.instance = new UiccController();
        UiccController.instance.port = new EuiccPort(sender);
        HookHarness.load("com.android.phone", 1001);
    }
    private Message request(Class<?> type, int card) throws Exception {
        Object body = type.getConstructor().newInstance();
        type.getField("mCallback").set(body, (EuiccConnector.BaseEuiccCommandCallback) () -> unavailable++);
        Message message = new Message(); message.arg1 = card; message.obj = body; return message;
    }
    private void metadata() throws Exception {
        connector.work = () -> {
            assertEquals(0, sender.command("challenge"));
            assertEquals(0, sender.command("info"));
            assertEquals("modeled challenge must survive between native calls", 0, sender.command("authenticate"));
        };
        connector.connected.processMessage(request(EuiccConnector.GetMetadataRequest.class, 17));
    }
    @Test public void metadataAndSubsequentDownloadShareARealChannelUntilUnbind() throws Exception {
        metadata();
        assertEquals(0, unavailable);
        assertEquals(1, sender.opens); assertEquals(0, sender.closes);
        connector.work = () -> assertEquals(0, sender.command("prepare"));
        connector.connected.processMessage(request(EuiccConnector.DownloadRequest.class, 17));
        assertEquals(1, sender.opens);
        connector.unbind();
        assertEquals(1, sender.closes); assertFalse(nativeSession.containsOwnedSession());
        assertFalse(HookHarness.logs.toString().contains(EID));
        assertFalse(HookHarness.logs.toString().contains(HASH));
    }
    @Test public void defaultListDiscoveryAlsoPreservesChallengeContext() throws Exception {
        connector.work = () -> { sender.command("challenge"); assertEquals(0, sender.command("authenticate")); };
        connector.connected.processMessage(request(EuiccConnector.GetDefaultListRequest.class, 17));
        assertTrue(nativeSession.containsOwnedSession()); connector.connected.exit(); assertFalse(nativeSession.containsOwnedSession());
    }
    @Test public void downloadWithoutMetadataAlsoStartsASession() throws Exception {
        connector.work = () -> { sender.command("challenge"); assertEquals(0, sender.command("authenticate")); };
        connector.connected.processMessage(request(EuiccConnector.DownloadRequest.class, 17));
        assertTrue(nativeSession.containsOwnedSession()); assertEquals(1, sender.opens);
    }
    @Test public void optOutRoutingOffAndOtherCardsRemainNative() throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) {
            setup();
            if (scenario == 0) HookHarness.preferences.remove(Selection.PRESERVE_SESSION);
            if (scenario == 1) HookHarness.preferences.put(Selection.KEY, "");
            connector.work = () -> { sender.command("challenge"); assertEquals(4, sender.command("authenticate")); };
            connector.connected.processMessage(request(EuiccConnector.GetMetadataRequest.class, scenario == 2 ? 4 : 17));
            assertFalse(nativeSession.containsOwnedSession()); assertEquals(1, connector.calls); assertEquals(0, unavailable);
        }
    }
    @Test public void otherLpaServicesRemainNative() throws Exception {
        connector.mSelectedComponent.packageName = "other.lpa";
        connector.work = () -> { sender.command("challenge"); assertEquals(4, sender.command("authenticate")); };
        connector.connected.processMessage(request(EuiccConnector.GetMetadataRequest.class, 17));
        assertFalse(nativeSession.containsOwnedSession()); assertEquals(1, connector.calls); assertEquals(0, unavailable);
    }
    @Test public void normalReadsNeverStartASession() {
        Message message = new Message(); message.obj = new Object(); message.arg1 = 17;
        connector.connected.processMessage(message);
        assertFalse(nativeSession.containsOwnedSession()); assertEquals(1, connector.calls);
    }
    @Test public void ourLeaseDoesNotKeepOtherCardsOpen() throws Exception {
        metadata();
        ApduSender other = new ApduSender();
        other.command("challenge"); assertEquals(4, other.command("authenticate"));
        assertEquals(2, other.closes); assertEquals(0, sender.closes);
        assertFalse("not a process-wide session override", nativeSession.hasSession());
    }
    @Test public void optimizationOverrideIsOnlyInsideOurStartCall() throws Exception {
        metadata(); connector.unbind();
        nativeSession.startSession("UNRELATED");
        assertFalse(nativeSession.hasSession()); assertFalse(nativeSession.optimized);
    }
    @Test public void cleanupDoesNotEndAnotherNativeOwnersSession() throws Exception {
        nativeSession.optimized = true; nativeSession.startSession("DOWNLOAD");
        metadata(); connector.connected.exit();
        assertTrue(nativeSession.hasSession()); assertEquals(0, sender.closes);
        nativeSession.endSession("DOWNLOAD"); assertEquals(1, sender.closes);
    }
    @Test public void foreignSessionCompletionStillClosesItsOtherCard() throws Exception {
        metadata();
        nativeSession.optimized = true; nativeSession.startSession("DOWNLOAD");
        ApduSender other = new ApduSender();
        other.command("challenge"); assertEquals(0, other.command("authenticate"));
        assertEquals(0, other.closes);
        nativeSession.endSession("DOWNLOAD");
        assertEquals(1, other.closes); assertEquals(0, sender.closes);
        assertTrue(nativeSession.containsOwnedSession());
        connector.unbind(); assertEquals(1, sender.closes); assertEquals(1, other.closes);
    }
    private void querySessionOnAnotherThread() {
        try { CompletableFuture.supplyAsync(nativeSession::hasSession).get(5, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new AssertionError("Native work must not wait on our monitor", failure); }
    }
    @Test public void inventoryResolutionDoesNotHoldTheSessionMonitor() throws Exception {
        metadata(); UiccController.instance.duringLookup = this::querySessionOnAnotherThread;
        metadata(); assertEquals(0, unavailable); assertEquals(2, connector.calls);
    }
    @Test public void channelCleanupDoesNotHoldTheSessionMonitor() throws Exception {
        metadata();
        sender.duringClose = () -> {
            nativeSession.optimized = true; nativeSession.startSession("FOREIGN");
            querySessionOnAnotherThread();
        };
        connector.unbind();
        assertEquals(1, sender.closes);
        assertFalse(HookHarness.logs.toString(), HookHarness.logs.toString().contains("CLEANUP_FAILED"));
    }
    @Test public void serviceDisconnectClosesOurSession() throws Exception {
        metadata(); connector.disconnected(); assertFalse(nativeSession.containsOwnedSession()); assertEquals(1, sender.closes);
        metadata(); assertEquals(2, sender.opens);
    }
    @Test public void nativeExceptionsArePreservedAndReleaseOurLease() throws Exception {
        RuntimeException failure = new IllegalStateException("native"); connector.failure = failure;
        Message request = request(EuiccConnector.GetMetadataRequest.class, 17);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> connector.connected.processMessage(request)));
        assertFalse(nativeSession.containsOwnedSession()); assertEquals(0, unavailable);
    }
    @Test public void nativeCardErrorsRemainErrorsWithoutRetry() throws Exception {
        connector.work = () -> assertEquals(4, sender.command("authenticate"));
        connector.connected.processMessage(request(EuiccConnector.GetMetadataRequest.class, 17));
        assertEquals(1, connector.calls); assertEquals(1, sender.opens); assertEquals(0, unavailable);
    }
    @Test public void sendExceptionDoesNotLeakThreadLocalSessionScope() throws Exception {
        metadata();
        IllegalStateException failure = new IllegalStateException("native sender"); sender.failure = failure;
        assertSame(failure, assertThrows(IllegalStateException.class, () -> sender.command("info")));
        assertFalse(nativeSession.hasSession());
    }
    @Test public void failedNativeSessionStartBlocksBeforeCallingTheLpa() throws Exception {
        nativeSession.rejectStart = true;
        connector.connected.processMessage(request(EuiccConnector.GetMetadataRequest.class, 17));
        assertEquals(1, unavailable); assertEquals(0, connector.calls); assertFalse(nativeSession.containsOwnedSession());
    }
    @Test public void movedOrRemovedTargetDoesNotFallBack() throws Exception {
        metadata();
        var cards = context.telephony.cards;
        context.telephony.cards = List.of();
        connector.connected.processMessage(request(EuiccConnector.GetMetadataRequest.class, 17));
        context.telephony.cards = cards;
        connector.connected.processMessage(request(EuiccConnector.GetMetadataRequest.class, 17));
        assertEquals(2, unavailable); assertEquals(1, connector.calls);
        connector.unbind(); assertEquals(1, sender.closes);
    }
    @Test public void replacingThePortObjectCannotReuseTheOldSession() throws Exception {
        metadata(); UiccController.instance.port = new EuiccPort(new ApduSender());
        connector.connected.processMessage(request(EuiccConnector.GetMetadataRequest.class, 17));
        assertEquals(1, unavailable); assertEquals(1, connector.calls);
    }
    @Test public void changingTheSessionOptionRequiresRestart() throws Exception {
        metadata(); HookHarness.preferences.put(Selection.PRESERVE_SESSION, false);
        connector.connected.processMessage(request(EuiccConnector.GetMetadataRequest.class, 17));
        HookHarness.preferences.put(Selection.PRESERVE_SESSION, true);
        connector.connected.processMessage(request(EuiccConnector.GetMetadataRequest.class, 17));
        assertEquals(2, unavailable); assertEquals(1, connector.calls);
    }
    @Test public void incompleteHookSetupFailsClosedButOptOutStillWorks() throws Exception {
        HookHarness.rejectHook = "optimizeApduSender";
        try { HookHarness.load("com.android.phone", 1001); }
        finally { HookHarness.rejectHook = null; }
        assertFalse(HookHarness.logs.toString().contains("READY:PHONE"));
        connector.connected.processMessage(request(EuiccConnector.GetMetadataRequest.class, 17));
        assertEquals(1, unavailable); assertEquals(0, connector.calls);
        setup(); HookHarness.preferences.remove(Selection.PRESERVE_SESSION);
        HookHarness.rejectHook = "optimizeApduSender";
        try { HookHarness.load("com.android.phone", 1001); }
        finally { HookHarness.rejectHook = null; }
        connector.connected.processMessage(request(EuiccConnector.GetMetadataRequest.class, 17));
        assertEquals(0, unavailable); assertEquals(1, connector.calls);
    }
    @Test public void repeatedPackageNotificationCannotHideAPartialInstall() throws Exception {
        HookHarness.rejectHook = "optimizeApduSender";
        TransferRouter module;
        try { module = HookHarness.load("com.android.phone", 1001); }
        finally { HookHarness.rejectHook = null; }
        HookHarness.notifyReady(module, "com.android.phone", 1001);
        assertFalse(HookHarness.logs.toString().contains("READY:PHONE"));
        connector.connected.processMessage(request(EuiccConnector.GetMetadataRequest.class, 17));
        assertEquals(1, unavailable); assertEquals(0, connector.calls);
    }
    @Test public void inlineCallersMustBeDeoptimizedBeforeReadiness() throws Exception {
        assertEquals(2, HookHarness.deoptimized.size());
        for (String method : List.of("startSession", "send")) {
            setup(); HookHarness.rejectDeoptimization = method;
            try { HookHarness.load("com.android.phone", 1001); }
            finally { HookHarness.rejectDeoptimization = null; }
            assertFalse(HookHarness.logs.toString().contains("READY:PHONE"));
            connector.connected.processMessage(request(EuiccConnector.GetMetadataRequest.class, 17));
            assertEquals(1, unavailable); assertEquals(0, connector.calls);
        }
    }
    @Test public void onlyTheOwnerPhonePackageLoadsSessionHooks() throws Exception {
        assertTrue(HookHarness.logs.toString(), HookHarness.logs.toString().contains("READY:PHONE"));
        HookHarness.load("com.android.phone", 101001); assertTrue(HookHarness.hooks.isEmpty());
        HookHarness.load("com.android.server.telecom", 1000); assertTrue(HookHarness.hooks.isEmpty());
    }
}
