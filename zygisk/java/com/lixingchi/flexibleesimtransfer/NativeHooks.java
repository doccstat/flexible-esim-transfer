package com.lixingchi.flexibleesimtransfer;

import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import io.github.libxposed.api.XposedInterface;

/** The small hook-chain contract used by the shared router, backed directly by LSPlant. */
public final class NativeHooks {
    static native Method hook(Method target, NativeHooks holder, Method callback);
    static native boolean deoptimize(Executable method);
    static native String settings();
    private final Method target;
    private final XposedInterface.Hooker handler;
    private Method backup;

    private NativeHooks(Method target, XposedInterface.Hooker handler) {
        this.target = target; this.handler = handler;
    }
    static NativeHooks install(Executable executable, XposedInterface.Hooker handler) throws Exception {
        if (!(executable instanceof Method method)) throw new IllegalArgumentException("Method required");
        method.setAccessible(true);
        NativeHooks holder = new NativeHooks(method, handler);
        synchronized (holder) {
            holder.backup = hook(method, holder, NativeHooks.class.getMethod("callback", Object[].class));
            if (holder.backup == null) throw new IllegalStateException("Hook unavailable");
            holder.backup.setAccessible(true);
        }
        return holder;
    }
    public Object callback(Object[] packed) throws Throwable {
        Method original;
        synchronized (this) { original = backup; }
        if (original == null) throw new IllegalStateException("Hook not initialized");
        boolean instance = !Modifier.isStatic(target.getModifiers());
        Object receiver = instance ? packed[0] : null;
        Object[] args = instance ? Arrays.copyOfRange(packed, 1, packed.length) : packed;
        return handler.intercept(new XposedInterface.Chain() {
            public Executable getExecutable() { return target; }
            public Object getThisObject() { return receiver; }
            public List<Object> getArgs() { return Arrays.asList(args); }
            public Object getArg(int index) { return args[index]; }
            public Object proceed() throws Throwable { return proceedWith(receiver, args); }
            public Object proceed(Object[] changed) throws Throwable { return proceedWith(receiver, changed); }
            public Object proceedWith(Object changed) throws Throwable { return proceedWith(changed, args); }
            public Object proceedWith(Object changed, Object[] arguments) throws Throwable {
                try { return original.invoke(changed, arguments); }
                catch (InvocationTargetException failure) { throw failure.getCause(); }
            }
        });
    }
}
