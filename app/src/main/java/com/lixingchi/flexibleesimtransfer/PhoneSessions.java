package com.lixingchi.flexibleesimtransfer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Message;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;

import io.github.libxposed.api.XposedInterface;

/** Owns real native channel leases, not card authentication state or trust decisions. */
// Runs inside the framework's Phone hook; private API shapes are checked before use.
@SuppressLint({"PrivateApi", "BlockedPrivateApi", "DiscouragedPrivateApi"})
final class PhoneSessions {
    private record Lease(Object session, String id, Object sender) { }
    private final Map<Object, Lease> leases = new IdentityHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final ThreadLocal<Object> starting = new ThreadLocal<>();
    private final ThreadLocal<Object> sending = new ThreadLocal<>();
    private final Method get, begin, end, controller, port, close;
    private final Field sessions, senders, sender;
    private final BiConsumer<String, String> log;
    private boolean ready;

    PhoneSessions(ClassLoader loader, BiConsumer<Executable, XposedInterface.Hooker> hook,
                  Function<Executable, Boolean> deoptimize,
                  Function<Context, Selection.Target> target, BiConsumer<String, String> log)
            throws ReflectiveOperationException {
        this.log = log;
        Class<?> session = Class.forName("com.android.internal.telephony.euicc.EuiccSession", false, loader);
        Method accessor;
        try { accessor = session.getDeclaredMethod("get", Context.class); }
        catch (NoSuchMethodException contextless) { accessor = session.getDeclaredMethod("get"); }
        get = accessible(accessor);
        begin = accessible(session.getDeclaredMethod("startSession", String.class));
        end = accessible(session.getDeclaredMethod("endSession", String.class));
        sessions = accessible(session.getDeclaredField("mSessions"));
        senders = accessible(session.getDeclaredField("mApduSenders"));
        Method hasSession = accessible(session.getDeclaredMethod("hasSession"));
        Class<?> uicc = Class.forName("com.android.internal.telephony.uicc.UiccController", false, loader);
        Class<?> euiccPort = Class.forName("com.android.internal.telephony.uicc.euicc.EuiccPort", false, loader);
        Class<?> apdu = Class.forName("com.android.internal.telephony.uicc.euicc.apdu.ApduSender", false, loader);
        controller = accessible(uicc.getDeclaredMethod("getInstance"));
        port = accessible(uicc.getDeclaredMethod("getUiccPortForSlot", int.class, int.class));
        sender = accessible(euiccPort.getDeclaredField("mApduSender"));
        close = accessible(apdu.getDeclaredMethod("closeAnyOpenChannel"));
        Method send = accessible(apdu.getDeclaredMethod("send",
                Class.forName(apdu.getPackage().getName() + ".RequestProvider", false, loader),
                Class.forName(apdu.getPackage().getName() + ".ApduSenderResultCallback", false, loader),
                android.os.Handler.class));
        Method gate;
        try { gate = session.getDeclaredMethod("optimizeApduSender"); }
        catch (NoSuchMethodException flagBased) {
            gate = Class.forName("com.android.internal.telephony.flags.Flags", false, loader)
                    .getDeclaredMethod("optimizationApduSender");
        }
        gate = accessible(gate);
        if (!Modifier.isStatic(get.getModifiers()) || get.getReturnType() != session
                || begin.getReturnType() != void.class || end.getReturnType() != void.class
                || gate.getReturnType() != boolean.class || hasSession.getReturnType() != boolean.class
                || !Set.class.isAssignableFrom(sessions.getType()) || !Set.class.isAssignableFrom(senders.getType())
                || sender.getType() != apdu || close.getReturnType() != void.class || send.getReturnType() != void.class)
            throw new NoSuchMethodException();
        Class<?> connector = Class.forName("com.android.internal.telephony.euicc.EuiccConnector", false, loader);
        Class<?> connected = Class.forName(connector.getName() + "$ConnectedState", false, loader);
        Field outer = null;
        for (Field field : connected.getDeclaredFields()) if (field.getType() == connector) {
            if (outer != null) throw new NoSuchFieldException();
            outer = accessible(field);
        }
        if (outer == null) throw new NoSuchFieldException();
        Field owner = outer, context = accessible(connector.getDeclaredField("mContext"));
        Field component = accessible(connector.getDeclaredField("mSelectedComponent"));
        if (!Context.class.isAssignableFrom(context.getType()) || component.getType() != ServiceInfo.class)
            throw new NoSuchFieldException();
        Class<?> callback = Class.forName(connector.getName() + "$BaseEuiccCommandCallback", false, loader);
        Method unavailable = accessible(callback.getMethod("onEuiccServiceUnavailable"));
        Map<Class<?>, Field> requests = new IdentityHashMap<>();
        for (String name : new String[]{"GetMetadataRequest", "GetDefaultListRequest", "DownloadRequest"}) {
            Class<?> request = Class.forName(connector.getName() + "$" + name, false, loader);
            Field field = accessible(request.getDeclaredField("mCallback"));
            if (!callback.isAssignableFrom(field.getType())) throw new NoSuchFieldException();
            requests.put(request, field);
        }
        Method process = accessible(connected.getDeclaredMethod("processMessage", Message.class));
        Method exit = accessible(connected.getDeclaredMethod("exit"));
        Method unbind = accessible(connector.getDeclaredMethod("unbind"));
        if (process.getReturnType() != boolean.class || exit.getReturnType() != void.class
                || unbind.getReturnType() != void.class) throw new NoSuchMethodException();

        // The dispatcher is a final guard if any later hook or deoptimization fails.
        hook.accept(process, chain -> {
            Message message = (Message) chain.getArg(0);
            Field callbackField = message.obj == null ? null : requests.get(message.obj.getClass());
            if (callbackField == null) return chain.proceed();
            Object connection = owner.get(chain.getThisObject());
            ServiceInfo service = (ServiceInfo) component.get(connection);
            if (service == null || !TransferRouter.LPA.equals(service.packageName)) return chain.proceed();
            boolean routed = false;
            try {
                Context ctx = (Context) context.get(connection);
                Selection.Target selected = target.apply(ctx);
                if (selected != null && selected.cardId() == message.arg1) {
                    if (!ready) throw new Selection.Blocked("HOOKS_INCOMPLETE");
                    acquire(connection, ctx, selected);
                    routed = true;
                }
            } catch (Throwable blocked) {
                log.accept("card_session", "UNAVAILABLE");
                invoke(unavailable, callbackField.get(message.obj));
                return true; // Native service-unavailable callback; never fabricate a result.
            }
            try { return chain.proceed(); }
            catch (Throwable nativeFailure) {
                if (routed) release(connection);
                throw nativeFailure;
            }
        });
        hook.accept(exit, chain -> {
            try { return chain.proceed(); }
            finally { release(owner.get(chain.getThisObject())); }
        });
        hook.accept(unbind, chain -> {
            try { return chain.proceed(); }
            finally { release(chain.getThisObject()); }
        });
        boolean staticGate = Modifier.isStatic(gate.getModifiers());
        hook.accept(gate, chain -> starting.get() != null
                && (staticGate || starting.get() == chain.getThisObject()) ? true : chain.proceed());
        hook.accept(send, chain -> {
            Object previous = sending.get();
            sending.set(chain.getThisObject());
            try { return chain.proceed(); }
            finally { if (previous == null) sending.remove(); else sending.set(previous); }
        });
        hook.accept(hasSession, chain -> {
            boolean nativeResult = (Boolean) chain.proceed();
            return nativeResult && appliesToSender(chain.getThisObject(), sending.get());
        });
        hook.accept(end, chain -> {
            Object result = chain.proceed();
            closeOtherSenders(chain.getThisObject());
            return result;
        });
        // These tiny private calls can be inlined into the native methods.
        if (!deoptimize.apply(begin) || !deoptimize.apply(send)) throw new IllegalStateException();
        ready = true;
    }

    private void acquire(Object connection, Context context, Selection.Target selected) throws Throwable {
        // Never hold our monitor across native inventory calls or channel-close waits.
        Object selectedPort = invoke(port, invoke(controller, null), selected.slot(), selected.port());
        Object selectedSender = sender.get(selectedPort);
        if (selectedSender == null) throw new IllegalStateException("Sender unavailable");
        Object session = get.getParameterCount() == 0 ? invoke(get, null) : invoke(get, null, context);
        Lease lease;
        synchronized (this) {
            Lease existing = leases.get(connection);
            if (existing != null) {
                if (!owns(existing) || existing.sender() != selectedSender)
                    throw new Selection.Blocked("CARD_MOVED_RESTART_REQUIRED");
                return;
            }
            lease = new Lease(session, "com.lixingchi.flexibleesimtransfer:" + sequence.incrementAndGet(), selectedSender);
            leases.put(connection, lease);
        }
        try {
            starting.set(session);
            try { invoke(begin, session, lease.id()); }
            finally { starting.remove(); }
            if (!owns(lease)) throw new IllegalStateException("Session unavailable");
            log.accept("card_session", "STARTED");
        } catch (Throwable failure) {
            release(connection);
            throw failure;
        }
    }

    private boolean owns(Lease lease) throws IllegalAccessException {
        synchronized (lease.session()) { return ((Set<?>) sessions.get(lease.session())).contains(lease.id()); }
    }

    private synchronized boolean appliesToSender(Object session, Object apdu) throws IllegalAccessException {
        if (leases.isEmpty()) return true;
        synchronized (session) {
            for (Object id : (Set<?>) sessions.get(session)) {
                Lease owned = owned(session, id);
                if (owned == null || owned.sender() == apdu) return true;
            }
            return false;
        }
    }

    private Lease owned(Object session, Object id) {
        for (Lease lease : leases.values())
            if (lease.session() == session && lease.id().equals(id)) return lease;
        return null;
    }

    private void closeOtherSenders(Object session) {
        try {
            ArrayList<Object> closing = new ArrayList<>();
            synchronized (this) {
                if (leases.isEmpty()) return;
                synchronized (session) {
                    Set<?> active = (Set<?>) sessions.get(session);
                    for (Object id : active) if (owned(session, id) == null) return;
                    // Our lease must not prolong another card's completed native session.
                    Set<?> open = (Set<?>) senders.get(session);
                    for (Object apdu : open) if (!appliesToSender(session, apdu)) closing.add(apdu);
                    open.removeAll(closing);
                }
            }
            for (Object apdu : closing) {
                try { invoke(close, apdu); }
                catch (Throwable failure) { log.accept("card_session", "CLEANUP_FAILED"); }
            }
        } catch (Throwable failure) { log.accept("card_session", "CLEANUP_FAILED"); }
    }

    private void release(Object connection) {
        Lease lease;
        synchronized (this) { lease = leases.get(connection); }
        if (lease == null) return;
        try {
            invoke(end, lease.session(), lease.id());
            synchronized (this) { leases.remove(connection, lease); }
            log.accept("card_session", "ENDED");
        } catch (Throwable failure) { log.accept("card_session", "CLEANUP_FAILED"); }
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) throws Throwable {
        try { return method.invoke(receiver, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private static Method accessible(Method method) { method.setAccessible(true); return method; }
    private static Field accessible(Field field) { field.setAccessible(true); return field; }
}
