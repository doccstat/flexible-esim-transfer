package com.lixingchi.flexibleesimtransfer;

import android.annotation.SuppressLint;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.UiccCardInfo;
import android.telephony.euicc.EuiccManager;
import android.util.Log;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Process-local destination routing; the phone service and source subscription inventory stay real. */
@SuppressLint({"PrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi", "MissingPermission"})
public final class TransferRouter extends XposedModule {
    private static final String LPA = "com.google.android.euicc";
    private static final String GMS = "com.google.android.gms";
    private static final String TAG = "FlexibleEsimTransfer";
    private final RoutingSession session = new RoutingSession();
    private final ThreadLocal<Boolean> inventoryRead = ThreadLocal.withInitial(() -> false);
    private final Set<Object> explicitManagers = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));
    private final Map<String, String> last = new ConcurrentHashMap<>();
    private final Set<Executable> installed = new HashSet<>();
    private SharedPreferences prefs;
    private volatile TelephonyManager telephony;
    private volatile Context context;
    private volatile boolean ready;

    @Override public void onPackageReady(PackageReadyParam param) {
        String name = param.getPackageName();
        if ((!LPA.equals(name) && !GMS.equals(name)) || !param.isFirstPackage()
                || param.getApplicationInfo().uid / 100000 != 0) return;
        try {
            prefs = getRemotePreferences(Selection.PREFS);
            install(Application.class.getDeclaredMethod("attach", Context.class), chain -> {
                context = (Context) chain.getArg(0);
                return chain.proceed();
            });
            // Install the final guards first: an incomplete setup must not continue a new download.
            if (LPA.equals(name)) installGuards(param.getClassLoader());
            installFramework();
            if (LPA.equals(name)) installLpa(param.getClassLoader());
            ready = true;
            emit("hooks", "READY:" + (LPA.equals(name) ? "LPA" : "GMS"));
        } catch (Throwable unavailable) {
            ready = false;
            emit("hooks", "INCOMPLETE;new_downloads_blocked_where_guards_loaded");
        }
    }

    private void installFramework() throws ReflectiveOperationException {
        install(TelephonyManager.class.getMethod("getUiccCardsInfo"), chain -> {
            @SuppressWarnings("unchecked") List<UiccCardInfo> cards = (List<UiccCardInfo>) chain.proceed();
            if (inventoryRead.get()) return cards;
            telephony = (TelephonyManager) chain.getThisObject();
            if (!enabled()) return cards;
            Selection.Target target = resolve(cards);
            List<UiccCardInfo> ordered = new ArrayList<>(cards);
            // Preserve every real card and its identity, including explicit source-card lookups.
            ordered.sort((a, b) -> Boolean.compare(b != null && b.getCardId() == target.cardId(),
                    a != null && a.getCardId() == target.cardId()));
            emit("inventory", "destination_first");
            return ordered;
        });
        install(EuiccManager.class.getMethod("createForCardId", int.class), chain -> {
            Object manager = chain.proceed();
            if (manager != null) explicitManagers.add(manager);
            return manager;
        });
        for (Method method : EuiccManager.class.getDeclaredMethods()) {
            String name = method.getName();
            boolean download = name.equals("downloadSubscription")
                    || name.equals("getDownloadableSubscriptionMetadata")
                    || name.equals("getDefaultDownloadableSubscriptionList")
                    || name.equals("continueOperation");
            boolean read = name.equals("getEid") || name.equals("getEuiccInfo")
                    || name.equals("getAvailableMemoryInBytes");
            boolean followUp = name.equals("switchToSubscription") || name.equals("updateSubscriptionNickname");
            if (!download && !read && !followUp) continue;
            install(method, chain -> {
                if (read && explicitManagers.contains(chain.getThisObject())) return chain.proceed();
                // -1 means disable, not enable a newly downloaded subscription. Never retarget it.
                if (followUp && (Integer) chain.getArg(0) < 0) return chain.proceed();
                Selection.Target target;
                EuiccManager destination;
                boolean route;
                try {
                    target = target();
                    route = target != null;
                    if (route && followUp) {
                        route = subscriptionOnTarget((Integer) chain.getArg(0), target);
                        if (route && method.getParameterTypes()[1] == int.class
                                && (Integer) chain.getArg(1) != target.port())
                            throw new Selection.Blocked("PORT_MISMATCH");
                    }
                    if (route && name.equals("continueOperation")) {
                        // Resolution may belong to a source-card operation: preserve its original binding.
                        Intent resolution = (Intent) chain.getArg(0);
                        route = resolution.getIntExtra("android.service.euicc.extra.RESOLUTION_CARD_ID", -1)
                                == target.cardId();
                    }
                    destination = route ? ((EuiccManager) chain.getThisObject())
                            .createForCardId(target.cardId()) : null;
                } catch (Throwable blocked) {
                    emit("blocked", reason(blocked));
                    if ((download || followUp) && sendFailure(chain)) return null;
                    throw new Selection.Blocked("DESTINATION_UNAVAILABLE");
                }
                if (!route) return chain.proceed();
                emit("manager", "routing:" + name);
                Object result = chain.proceedWith(destination);
                if (name.equals("getEid")) target.requireEid((String) result);
                return result;
            });
        }
    }

    private void installGuards(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> service = Class.forName("com.android.euicc.service.EuiccServiceImpl", false, loader);
        int downloads = 0;
        int metadata = 0;
        for (Method method : service.getDeclaredMethods()) {
            boolean download = method.getName().equals("onDownloadSubscription");
            boolean read = method.getName().equals("onGetDownloadableSubscriptionMetadata");
            if (!download && !read) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length < 2 || types[0] != int.class) throw new NoSuchMethodException();
            boolean hasPort = types[1] == int.class;
            install(method, chain -> {
                Selection.Target target;
                try {
                    target = target();
                    if (target != null) {
                        int port = hasPort ? (Integer) chain.getArg(1) : 0;
                        if (download) target.requireAddress((Integer) chain.getArg(0), port);
                        else if (target.slot() != (Integer) chain.getArg(0))
                            throw new Selection.Blocked("ADDRESS_MISMATCH");
                        emit("service", "destination_verified");
                    }
                } catch (Throwable blocked) {
                    emit("blocked", reason(blocked));
                    // Positive EuiccService result = failure, never resolvable or success.
                    if (method.getReturnType() == int.class) return 1;
                    if (download) return method.getReturnType()
                            .getConstructor(int.class, int.class, int.class).newInstance(1, 0, -1);
                    return method.getReturnType().getConstructor(int.class,
                            android.telephony.euicc.DownloadableSubscription.class).newInstance(1, null);
                }
                Object result = chain.proceed();
                if (target == null) return result;
                try {
                    Object code = method.getReturnType() == int.class ? result
                            : method.getReturnType().getMethod("getResult").invoke(result);
                    emit("service_result", (download ? "download=" : "metadata=") + code);
                } catch (Throwable unavailable) {
                    emit("service_result", "UNAVAILABLE");
                }
                return result;
            });
            if (download) downloads++; else metadata++;
        }
        if (downloads == 0 || metadata == 0) throw new NoSuchMethodException();
        Class<?> cardManager = Class.forName("android.telephony.euicc.EuiccCardManager", false, loader);
        Class<?> callback = Class.forName("android.telephony.euicc.EuiccCardManager$ResultCallback", false, loader);
        Method complete = callback.getMethod("onComplete", int.class, Object.class);
        for (String name : List.of("requestEuiccChallenge", "authenticateServer", "prepareDownload",
                "loadBoundProfilePackage")) {
            boolean found = false;
            for (Method method : cardManager.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                Class<?>[] types = method.getParameterTypes();
                int count = types.length;
                if (count < 3 || types[0] != String.class || types[count - 2] != Executor.class
                        || types[count - 1] != callback) throw new NoSuchMethodException();
                install(method, chain -> {
                    try {
                        Selection.Target target = target();
                        if (target != null) {
                            target.requireEid((String) chain.getArg(0));
                            emit("card", "destination_verified:" + name);
                        }
                    } catch (Throwable blocked) {
                        emit("blocked", reason(blocked));
                        Executor executor = (Executor) chain.getArg(count - 2);
                        Object receiver = chain.getArg(count - 1);
                        executor.execute(() -> {
                            try { complete.invoke(receiver, -1, null); } // RESULT_UNKNOWN_ERROR
                            catch (ReflectiveOperationException failure) {
                                throw new Selection.Blocked("ERROR_CALLBACK_FAILED");
                            }
                        });
                        return null;
                    }
                    return chain.proceed();
                });
                found = true;
            }
            if (!found) throw new NoSuchMethodException();
        }
    }

    private void installLpa(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> app = Class.forName("com.android.euicc.EuiccApplication", false, loader);
        Class<?> slots = null;
        Class<?> mappingBase = null;
        for (Method method : app.getDeclaredMethods()) {
            if (method.getParameterCount() != 0) continue;
            if (method.getName().startsWith("getUiccSlotsManager")) slots = method.getReturnType();
            if (method.getName().startsWith("getSimSlotMapping")) mappingBase = method.getReturnType();
        }
        if (slots == null || mappingBase == null) throw new NoSuchMethodException();
        Method tm = slots.getDeclaredMethod("getTelephonyManager");
        tm.setAccessible(true);
        for (String name : List.of("getActiveEuiccSlotId", "getFirstActiveEuiccSlotId",
                "getFirstActiveEuiccLogicalSlotId", "getFirstActiveEuiccSlotIdAndPort")) {
            install(slots.getDeclaredMethod(name), chain -> {
                telephony = (TelephonyManager) tm.invoke(chain.getThisObject());
                Selection.Target target = target();
                if (target == null) return chain.proceed();
                if (name.equals("getFirstActiveEuiccLogicalSlotId")) return target.logicalSlot();
                if (name.equals("getFirstActiveEuiccSlotIdAndPort"))
                    return new AbstractMap.SimpleImmutableEntry<>(target.slot(), target.port());
                return target.slot();
            });
        }
        Class<?> slotArray = java.lang.reflect.Array.newInstance(
                Class.forName("android.telephony.UiccSlotInfo", false, loader), 0).getClass();
        install(slots.getDeclaredMethod("getPreferredEuiccSlotId", slotArray), chain -> {
            telephony = (TelephonyManager) tm.invoke(chain.getThisObject());
            Selection.Target target = target();
            return target == null ? chain.proceed() : target.slot();
        });
        install(slots.getDeclaredMethod("getEidForSlot", int.class), chain -> {
            if ((Integer) chain.getArg(0) != -1) return chain.proceed();
            telephony = (TelephonyManager) tm.invoke(chain.getThisObject());
            Selection.Target target = target();
            if (target == null) return chain.proceed();
            Object result = chain.proceed(new Object[] {target.slot()});
            target.requireEid((String) result);
            return result;
        });
        Class<?> mapping = Class.forName("com.google.android.euicc.config.SimSlotMapping", false, loader);
        for (Class<?> type : List.of(mappingBase, mapping)) {
            install(type.getMethod("getEsimSlotIds"), chain -> {
                Selection.Target target = target();
                return target == null ? chain.proceed() : new ArrayList<>(List.of(target.slot()));
            });
            install(type.getMethod("getPsimSlotIds"), chain -> {
                Selection.Target target = target();
                Object original = chain.proceed();
                if (target == null || original == null) return original;
                List<?> physical = new ArrayList<>((List<?>) original);
                physical.remove(Integer.valueOf(target.slot()));
                return physical;
            });
        }
    }

    private boolean enabled() {
        if (prefs == null) throw new Selection.Blocked("SETTINGS_UNAVAILABLE");
        Map<String, ?> values = prefs.getAll();
        Object selected = values.get(Selection.KEY);
        Object port = values.get(Selection.PORT);
        if (!session.enabled(selected == null ? "" : (String) selected, port == null ? "" : (String) port))
            return false;
        if (!ready) throw new Selection.Blocked("HOOKS_INCOMPLETE");
        return true;
    }

    private Selection.Target target() {
        if (!enabled()) return null;
        if (telephony == null && context != null) telephony = context.getSystemService(TelephonyManager.class);
        if (telephony == null) throw new Selection.Blocked("CONTEXT_UNAVAILABLE");
        inventoryRead.set(true);
        try { return resolve(telephony.getUiccCardsInfo()); }
        catch (Throwable unavailable) {
            session.unavailable();
            if (unavailable instanceof Selection.Blocked blocked) throw blocked;
            throw new Selection.Blocked("INVENTORY_UNAVAILABLE");
        } finally { inventoryRead.remove(); }
    }

    private Selection.Target resolve(List<UiccCardInfo> inventory) {
        try {
            Map<String, ?> values = prefs.getAll();
            String selected = (String) values.getOrDefault(Selection.KEY, null);
            Object port = values.get(Selection.PORT);
            String portText = port == null ? "" : (String) port;
            if (!session.enabled(selected, portText)) throw new Selection.Blocked("SETTINGS_CHANGED");
            List<Selection.Card> cards = null;
            if (inventory != null) {
                cards = new ArrayList<>();
                for (UiccCardInfo card : inventory) {
                    if (card == null || !card.isEuicc()) continue;
                    String fingerprint;
                    try { fingerprint = Selection.fingerprint(card.getEid()); }
                    catch (IllegalArgumentException redacted) { continue; }
                    List<Selection.Port> ports = new ArrayList<>();
                    for (android.telephony.UiccPortInfo p : card.getPorts())
                        ports.add(new Selection.Port(p.getPortIndex(), p.getLogicalSlotIndex(), p.isActive()));
                    cards.add(new Selection.Card(fingerprint, card.getCardId(), card.getPhysicalSlotIndex(), true, ports));
                }
            }
            return session.pin(Selection.resolve(selected, portText, cards));
        } catch (Throwable unavailable) {
            session.unavailable();
            if (unavailable instanceof Selection.Blocked blocked) throw blocked;
            throw new Selection.Blocked("INVENTORY_UNAVAILABLE");
        }
    }

    private boolean subscriptionOnTarget(int subscriptionId, Selection.Target target) throws ReflectiveOperationException {
        if (context == null) throw new Selection.Blocked("CONTEXT_UNAVAILABLE");
        SubscriptionManager manager = context.getSystemService(SubscriptionManager.class);
        @SuppressWarnings("unchecked") List<SubscriptionInfo> subscriptions = (List<SubscriptionInfo>)
                SubscriptionManager.class.getMethod("getAvailableSubscriptionInfoList").invoke(manager);
        if (subscriptions == null) throw new Selection.Blocked("SUBSCRIPTIONS_UNAVAILABLE");
        for (SubscriptionInfo subscription : subscriptions)
            if (subscription.getSubscriptionId() == subscriptionId)
                return subscription.getCardId() == target.cardId();
        throw new Selection.Blocked("SUBSCRIPTION_NOT_FOUND");
    }

    private boolean sendFailure(XposedInterface.Chain chain) throws PendingIntent.CanceledException {
        PendingIntent callback = null;
        for (Object arg : chain.getArgs()) {
            if (arg instanceof PendingIntent pending) callback = pending;
            if (arg instanceof Intent intent) callback = intent.getParcelableExtra(
                    "android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_RESOLUTION_CALLBACK_INTENT", PendingIntent.class);
        }
        if (callback == null) return false;
        callback.send(context, EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                new Intent().putExtra("com.lixingchi.flexibleesimtransfer.ROUTING_ERROR", true));
        return true;
    }

    private void install(Executable method, XposedInterface.Hooker hooker) {
        if (installed.contains(method)) return;
        hook(method).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(hooker);
        installed.add(method);
    }

    private static String reason(Throwable error) {
        return error instanceof Selection.Blocked ? error.getMessage() : "UNAVAILABLE";
    }

    private void emit(String event, String message) {
        try {
            if (!message.equals(last.put(event, message))) log(Log.INFO, TAG, event + ": " + message);
        } catch (Throwable unavailable) { /* Logging must not alter a native result. */ }
    }
}
