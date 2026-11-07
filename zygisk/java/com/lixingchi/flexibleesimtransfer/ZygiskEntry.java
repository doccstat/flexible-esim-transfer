package com.lixingchi.flexibleesimtransfer;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import io.github.libxposed.api.*;

/** Loaded only after native owner/package/process selection; no Vector service dependency. */
public final class ZygiskEntry {
    private static final List<NativeHooks> hooks = new ArrayList<>();
    private static TransferRouter router;

    public static void start(String expectedPackage) throws Exception {
        BackendGuard.enter("zygisk");
        NativeHooks.install(Instrumentation.class.getDeclaredMethod("newApplication", ClassLoader.class,
                String.class, Context.class), chain -> {
            Context context = (Context) chain.getArg(2);
            if (expectedPackage.equals(context.getPackageName())) {
                try { initialize(context, (ClassLoader) chain.getArg(0)); }
                catch (Throwable unavailable) { Log.i("FlexibleEsimTransfer", "hooks: INCOMPLETE;new_downloads_blocked_where_guards_loaded"); }
            }
            return chain.proceed();
        });
        for (Method caller : Class.forName("android.app.LoadedApk").getDeclaredMethods()) {
            if (caller.getName().startsWith("makeApplication") && !NativeHooks.deoptimize(caller))
                throw new IllegalStateException("Application bootstrap unavailable");
        }
    }

    private static synchronized void initialize(Context context, ClassLoader loader) throws Exception {
        if (router != null) return;
        SharedPreferences preferences = proxy(SharedPreferences.class, (object, method, args) -> {
            if (method.getName().equals("getAll")) return ModuleConfig.parse(NativeHooks.settings()).values();
            throw new UnsupportedOperationException();
        });
        XposedInterface framework = proxy(XposedInterface.class, (object, method, args) -> switch (method.getName()) {
            case "getRemotePreferences" -> preferences;
            case "log" -> { Log.println((Integer) args[0], (String) args[1], (String) args[2]); yield null; }
            case "deoptimize" -> NativeHooks.deoptimize((Executable) args[0]);
            case "hook" -> {
                Executable target = (Executable) args[0];
                yield proxy(XposedInterface.HookBuilder.class, (builder, action, parameters) -> {
                    if (action.getName().equals("intercept")) {
                        hooks.add(NativeHooks.install(target, (XposedInterface.Hooker) parameters[0]));
                        return null; // The shared router never uses an unhook handle.
                    }
                    if (action.getName().equals("setExceptionMode")) return builder;
                    throw new UnsupportedOperationException();
                });
            }
            default -> throw new UnsupportedOperationException();
        });
        router = new TransferRouter();
        router.standalone(ctx -> {
            if (ctx.getPackageName().equals("com.android.phone")) InventorySocket.start(router::inventory);
        });
        // This is the standalone framework adapter, not an Xposed module attaching itself.
        for (Method attach : XposedInterfaceWrapper.class.getDeclaredMethods()) {
            if (!attach.getName().equals("attachFramework")) continue;
            if (attach.getParameterCount() == 1) attach.invoke(router, framework);
            else attach.invoke(router, framework, (Runnable) () -> {});
        }
        router.onPackageReady(proxy(XposedModuleInterface.PackageReadyParam.class, (object, method, args) -> switch (method.getName()) {
            case "getPackageName" -> context.getPackageName();
            case "getApplicationInfo" -> context.getApplicationInfo();
            case "getClassLoader" -> loader;
            case "isFirstPackage" -> true;
            default -> throw new UnsupportedOperationException();
        }));
    }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(ZygiskEntry.class.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
