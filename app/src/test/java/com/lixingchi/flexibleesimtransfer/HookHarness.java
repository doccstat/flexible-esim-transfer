package com.lixingchi.flexibleesimtransfer;

import android.content.SharedPreferences;
import java.lang.reflect.*;
import java.util.*;
import io.github.libxposed.api.*;

/** Runs the production interceptors against small Android/LPA doubles, not ART. */
public final class HookHarness {
    public interface Original { Object run(Object receiver, Object[] args) throws Throwable; }
    public static final Map<Executable, XposedInterface.Hooker> hooks = new HashMap<>();
    public static final List<String> logs = new ArrayList<>();
    public static final Map<String, Object> preferences = new HashMap<>();
    public static String rejectHook, rejectDeoptimization;
    public static final Set<Executable> deoptimized = new HashSet<>();

    @SuppressWarnings("unchecked")
    public static <T> T call(Class<?> owner, String name, Object receiver, Class<?>[] types,
                             Object[] args, Original original) {
        try {
            Method method = owner.getDeclaredMethod(name, types);
            XposedInterface.Hooker hook = hooks.get(method);
            if (hook == null) return (T) original.run(receiver, args);
            return (T) hook.intercept(new XposedInterface.Chain() {
                public Executable getExecutable() { return method; }
                public Object getThisObject() { return receiver; }
                public List<Object> getArgs() { return Arrays.asList(args); }
                public Object getArg(int i) { return args[i]; }
                public Object proceed() throws Throwable { return original.run(receiver, args); }
                public Object proceed(Object[] a) throws Throwable { return original.run(receiver, a); }
                public Object proceedWith(Object r) throws Throwable { return original.run(r, args); }
                public Object proceedWith(Object r, Object[] a) throws Throwable { return original.run(r, a); }
            });
        } catch (Throwable error) { return HookHarness.<T, RuntimeException>rethrow(error); }
    }
    @SuppressWarnings("unchecked") private static <T, E extends Throwable> T rethrow(Throwable e) throws E { throw (E) e; }

    public static TransferRouter load(String packageName, int uid) throws Exception {
        hooks.clear(); logs.clear(); deoptimized.clear();
        TransferRouter module = new TransferRouter();
        SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(HookHarness.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (o, m, a) -> {
                    if (m.getName().equals("getAll")) return new HashMap<>(preferences);
                    throw new UnsupportedOperationException(m.getName());
                });
        XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(HookHarness.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (o, m, a) -> {
                    if (m.getName().equals("getRemotePreferences")) return prefs;
                    if (m.getName().equals("log")) { logs.add(Arrays.toString(a)); return null; }
                    if (m.getName().equals("deoptimize")) {
                        Executable method = (Executable) a[0];
                        if (method.getName().equals(rejectDeoptimization)) return false;
                        deoptimized.add(method); return true;
                    }
                    if (m.getName().equals("hook")) {
                        Executable method = (Executable) a[0];
                        if (method.getName().equals(rejectHook)) throw new IllegalStateException("hook rejected");
                        return Proxy.newProxyInstance(HookHarness.class.getClassLoader(),
                                new Class<?>[]{XposedInterface.HookBuilder.class}, (builder, bm, ba) -> {
                                    if (bm.getName().equals("intercept")) { hooks.put(method, (XposedInterface.Hooker) ba[0]); return null; }
                                    return builder;
                                });
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        // This is a test framework, not a module calling its own attachment API.
        for (Method attach : XposedInterfaceWrapper.class.getDeclaredMethods()) {
            if (!attach.getName().equals("attachFramework")) continue;
            if (attach.getParameterCount() == 1) attach.invoke(module, framework);
            else attach.invoke(module, framework, (Runnable) () -> {});
        }
        notifyReady(module, packageName, uid);
        return module;
    }

    public static void notifyReady(TransferRouter module, String packageName, int uid) {
        android.content.pm.ApplicationInfo info = new android.content.pm.ApplicationInfo();
        info.uid = uid;
        XposedModuleInterface.PackageReadyParam param = (XposedModuleInterface.PackageReadyParam)
                Proxy.newProxyInstance(HookHarness.class.getClassLoader(),
                        new Class<?>[]{XposedModuleInterface.PackageReadyParam.class}, (o, m, a) -> switch (m.getName()) {
                            case "getPackageName" -> packageName;
                            case "isFirstPackage" -> true;
                            case "getApplicationInfo" -> info;
                            case "getClassLoader" -> HookHarness.class.getClassLoader();
                            default -> throw new UnsupportedOperationException(m.getName());
                        });
        module.onPackageReady(param);
    }
}
