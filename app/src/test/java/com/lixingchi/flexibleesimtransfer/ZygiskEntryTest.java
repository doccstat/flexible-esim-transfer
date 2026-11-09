package com.lixingchi.flexibleesimtransfer;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.telephony.TelephonyManager;
import android.telephony.UiccCardInfo;
import android.telephony.UiccPortInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ZygiskEntryTest {
    private final Context context = new Context();
    private final List<String> calls = new ArrayList<>();

    @Before public void reset() {
        HookHarness.hooks.clear(); HookHarness.logs.clear(); HookHarness.preferences.clear();
        HookHarness.rejectHook = null; HookHarness.rejectDeoptimization = null;
    }
    @After public void clearBackend() { System.clearProperty("com.lixingchi.flexibleesimtransfer.backend"); }

    private void hook(ZygiskEntry.Initializer initialize, Consumer<Context> attached) throws Exception {
        HookHarness.hooks.put(Application.class.getDeclaredMethod("attach", Context.class),
                ZygiskEntry.applicationHook("com.google.android.euicc", initialize, attached));
    }
    private Object attach(HookHarness.Original nativeAttach) {
        return HookHarness.call(Application.class, "attach", new Application(), new Class<?>[]{Context.class},
                new Object[]{context}, nativeAttach);
    }
    private void assertRedactedFailure() {
        assertEquals(List.of("FlexibleEsimTransfer: bootstrap: FAILED:APPLICATION",
                "FlexibleEsimTransfer: hooks: INCOMPLETE;new_downloads_blocked_where_guards_loaded"), HookHarness.logs);
    }

    @Test public void initializationPrecedesNativeAttachAndInventoryFollowsIt() throws Exception {
        hook(ctx -> { assertSame(context, ctx); calls.add("initialize"); }, ctx -> {
            assertSame(context, ctx); calls.add("inventory");
        });
        Object result = new Object();
        assertSame(result, attach((receiver, args) -> {
            assertSame(context, args[0]); calls.add("native"); return result;
        }));
        assertEquals(List.of("initialize", "native", "inventory"), calls);
        assertTrue(HookHarness.logs.isEmpty());
    }
    @Test public void anotherPackageOnlyRunsItsNativeAttach() throws Exception {
        context.hostPackage = "other.package";
        hook(ctx -> fail("wrong package initialized"), ctx -> fail("wrong package exposed inventory"));
        attach((receiver, args) -> { calls.add("native"); return null; });
        assertEquals(List.of("native"), calls);
        assertTrue(HookHarness.logs.isEmpty());
    }
    @Test public void failedInitializationDoesNotCrashNativeAttachOrPublishInventory() throws Exception {
        hook(ctx -> { throw new IllegalStateException("private initialization detail"); },
                ctx -> fail("failed initialization exposed inventory"));
        Object result = new Object();
        assertSame(result, attach((receiver, args) -> { calls.add("native"); return result; }));
        assertEquals(List.of("native"), calls);
        assertRedactedFailure();
    }
    @Test public void nativeFailureIsNotCaughtRetriedOrFollowedByInventory() throws Exception {
        hook(ctx -> calls.add("initialize"), ctx -> fail("failed native attach exposed inventory"));
        RuntimeException failure = new SecurityException("native permission failure");
        assertSame(failure, assertThrows(SecurityException.class, () -> attach((receiver, args) -> {
            calls.add("native"); throw failure;
        })));
        assertEquals(List.of("initialize", "native"), calls);
        assertTrue(HookHarness.logs.isEmpty());
    }
    @Test public void inventoryStartupFailureDoesNotReplaceNativeResult() throws Exception {
        hook(ctx -> calls.add("initialize"), ctx -> { throw new IllegalStateException("private listener detail"); });
        Object result = new Object();
        assertSame(result, attach((receiver, args) -> { calls.add("native"); return result; }));
        assertEquals(List.of("initialize", "native"), calls);
        assertRedactedFailure();
    }
    @Test public void standaloneUsesSuppliedContextWithoutInstallingASecondAttachHook() throws Exception {
        context.telephony = new TelephonyManager();
        context.telephony.cards = List.of(new UiccCardInfo(SelectionTest.EID, 17, 1, true, true,
                List.of(new UiccPortInfo(0, 1, true))));
        for (String name : List.of("com.android.phone", "com.google.android.euicc", "com.google.android.gms")) {
            TransferRouter router = new TransferRouter();
            router.standalone(context);
            HookHarness.load(router, name, name.equals("com.android.phone") ? 1001 : 10001);
            assertFalse(HookHarness.hooks.containsKey(Application.class.getDeclaredMethod("attach", Context.class)));
            assertTrue(HookHarness.logs.toString(), HookHarness.logs.stream().anyMatch(s -> s.contains("READY:")));
            assertEquals(CardInventory.fromTelephony(context.telephony.cards), router.inventory());
            assertSame(context.telephony.cards, context.telephony.getUiccCardsInfo());
            assertEquals(0, context.registrations);
        }
    }
}
