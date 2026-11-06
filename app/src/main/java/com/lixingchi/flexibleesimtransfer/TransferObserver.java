package com.lixingchi.flexibleesimtransfer;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.telephony.UiccCardInfo;
import android.telephony.euicc.DownloadableSubscription;
import android.telephony.euicc.EuiccManager;
import android.util.Log;

import java.io.FileInputStream;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public final class TransferObserver extends XposedModule {
    private static final String TAG = "FlexibleEsimTransfer";
    private static final String LPA = "com.google.android.euicc";
    private static final String REVIEWED_APK =
            "579f75da460e6a6137a1f2b39e603e20999b4f55c2ec1bde51acc4feac1c6760";
    private final Map<String, String> last = new ConcurrentHashMap<>();
    private SharedPreferences prefs;

    // Vector permits reflection in the injected process; failures remain observation-only.
    @SuppressLint({"BlockedPrivateApi", "PrivateApi"})
    @Override public void onPackageReady(PackageReadyParam param) {
        if (!LPA.equals(param.getPackageName()) || !param.isFirstPackage()
                || param.getApplicationInfo().uid / 100000 != 0) return;
        try {
            prefs = getRemotePreferences(Selection.PREFS);
            install(TelephonyManager.class.getMethod("getCardIdForDefaultEuicc"), "default_card",
                    (chain, result, selected) -> snapshot((TelephonyManager) chain.getThisObject(), selected)
                            .compareCard((Integer) result));
            install(EuiccManager.class.getMethod("getEid"), "manager_eid",
                    (chain, result, selected) -> Selection.compareEid(selected, (String) result));
            install(EuiccManager.class.getMethod("createForCardId", int.class), "explicit_card",
                    (chain, result, selected) -> snapshot(telephony(chain.getThisObject()), selected)
                            .compareCard((Integer) chain.getArg(0)));
            install(EuiccManager.class.getMethod("downloadSubscription", DownloadableSubscription.class,
                    boolean.class, PendingIntent.class), "download_request_returned",
                    (chain, result, selected) -> {
                        Field field = EuiccManager.class.getDeclaredField("mCardId");
                        field.setAccessible(true);
                        return snapshot(telephony(chain.getThisObject()), selected)
                                .compareCard(field.getInt(chain.getThisObject()));
                    });
        } catch (Throwable unavailable) {
            emit("framework_hooks", "INCOMPLETE;observation_only");
            return;
        }
        emit("framework_hooks", "installed;observation_only");
        if (Build.VERSION.SDK_INT != 37 || !reviewedApk(param.getApplicationInfo().sourceDir)) {
            emit("lpa_hooks", "unsupported_build;framework_observation_only");
            return;
        }
        try {
            ClassLoader loader = param.getClassLoader();
            Class<?> slots = Class.forName("com.airbnb.lottie.network.NetworkFetcher", false, loader);
            Method getTelephony = slots.getDeclaredMethod("getTelephonyManager");
            getTelephony.setAccessible(true);
            for (String name : List.of("getActiveEuiccSlotId", "getFirstActiveEuiccSlotId")) {
                install(slots.getDeclaredMethod(name), name,
                        (chain, result, selected) -> snapshot(
                                (TelephonyManager) getTelephony.invoke(chain.getThisObject()), selected)
                                .compareSlot((Integer) result));
            }
            Class<?> slotArray = java.lang.reflect.Array.newInstance(
                    Class.forName("android.telephony.UiccSlotInfo"), 0).getClass();
            install(slots.getDeclaredMethod("getPreferredEuiccSlotId", slotArray), "preferred_slot",
                    (chain, result, selected) -> snapshot(
                            (TelephonyManager) getTelephony.invoke(chain.getThisObject()), selected)
                            .compareSlot((Integer) result));
            Class<?> info = Class.forName("com.google.android.gms.esim.DeviceInfo", false, loader);
            install(info.getDeclaredConstructor(String.class, String.class, String.class,
                    String.class, String.class, int.class, int.class, List.class, String.class,
                    String.class), "transfer_metadata_target",
                    (chain, result, selected) -> Selection.compareEid(selected, (String) chain.getArg(4)));
            emit("lpa_hooks", "installed;observation_only");
        } catch (Throwable unavailable) {
            emit("lpa_hooks", "INCOMPLETE;observation_only");
        }
    }

    private interface Inspection {
        String inspect(XposedInterface.Chain chain, Object result, String selected) throws Throwable;
    }

    private void install(Executable method, String event, Inspection inspection) {
        hook(method).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> AfterCall.run(chain::proceed, result -> {
                    try {
                        String selected = prefs.getString(Selection.KEY, "");
                        if (Selection.configured(selected)) emit(event, inspection.inspect(chain, result, selected));
                    } catch (Throwable unavailable) {
                        emit(event, "inspection=UNAVAILABLE");
                    }
                }));
    }

    @SuppressLint("SoonBlockedPrivateApi") // Injected by Vector, never called from the settings app.
    private static TelephonyManager telephony(Object manager) throws ReflectiveOperationException {
        Field field = EuiccManager.class.getDeclaredField("mContext");
        field.setAccessible(true);
        return ((Context) field.get(manager)).getSystemService(TelephonyManager.class);
    }

    @SuppressLint("MissingPermission") // The host LPA owns the permission; denial is caught by inspection.
    private static Selection.Result snapshot(TelephonyManager manager, String selected) {
        List<UiccCardInfo> inventory = manager.getUiccCardsInfo();
        if (inventory == null) return Selection.resolve(selected, null);
        List<Selection.Card> cards = new ArrayList<>();
        for (UiccCardInfo card : inventory) {
            if (card == null || !card.isEuicc()) continue;
            String fingerprint;
            try {
                fingerprint = Selection.fingerprint(card.getEid());
            } catch (IllegalArgumentException unavailable) {
                continue;
            }
            boolean active = card.getPorts().stream().anyMatch(port -> port.isActive());
            cards.add(new Selection.Card(fingerprint, card.getCardId(), card.getPhysicalSlotIndex(),
                    card.isEuicc(), card.isRemovable(), active));
        }
        return Selection.resolve(selected, cards);
    }

    private void emit(String event, String message) {
        if (!message.equals(last.put(event, message))) log(Log.INFO, TAG, event + ": " + message);
    }

    private static boolean reviewedApk(String path) {
        try (FileInputStream input = new FileInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return REVIEWED_APK.contentEquals(hex);
        } catch (Exception unavailable) {
            return false;
        }
    }
}
