package com.lixingchi.flexibleesimtransfer;

import org.junit.After;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import io.github.libxposed.api.XposedInterface;
import static org.junit.Assert.*;

public class StandaloneTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    @After public void reset() { System.clearProperty("com.lixingchi.flexibleesimtransfer.backend"); }
    @Test public void eitherBackendWorksAloneButMixedBackendsBlock() {
        BackendGuard.enter("vector"); BackendGuard.enter("vector"); BackendGuard.check();
        BackendGuard.enter("zygisk");
        assertThrows(Selection.Blocked.class, BackendGuard::check);
        reset(); BackendGuard.enter("zygisk"); BackendGuard.check();
        BackendGuard.enter("vector"); assertThrows(Selection.Blocked.class, BackendGuard::check);
    }
    @Test public void exactConfigurationRoundTripsAndOffIsExplicit() {
        var config = new ModuleConfig("a".repeat(64), "00", true);
        assertEquals(config, ModuleConfig.parse(config.encode()));
        assertEquals("00", config.values().get(Selection.PORT));
        assertEquals(new ModuleConfig("", "", false), ModuleConfig.parse("FET1\n\n\n0\n"));
    }
    @Test public void missingInvalidOrAmbiguousConfigurationNeverMeansOff() {
        for (String text : new String[]{null, "", "FET1\n\n\n0", "FET1\n\n\n0\nextra", "FET1\n\n\n2\n", "FET1\n\n\n1\n"})
            assertThrows(RuntimeException.class, () -> ModuleConfig.parse(text));
        assertThrows(RuntimeException.class, () -> new ModuleConfig("a".repeat(64), " 0", false));
        assertThrows(Selection.Blocked.class, () -> ModuleConfig.parse("FET1\ninvalid\n0\n0\n"));
    }
    @Test public void reportsKeepExactNamesAndRefuseReplacementOrTraversal() throws Exception {
        Path report = files.newFile("private.jsonl").toPath();
        Files.write(report, "redacted fixture\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path directory = files.newFolder("exports").toPath();
        Path exported = ModuleFiles.export(report, directory, "chosen name.jsonl");
        assertEquals(directory.resolve("chosen name.jsonl"), exported);
        assertArrayEquals(Files.readAllBytes(report), Files.readAllBytes(exported));
        assertThrows(FileAlreadyExistsException.class, () -> ModuleFiles.export(report, directory, "chosen name.jsonl"));
        for (String name : new String[]{"", ".", "..", "../outside", "sub/file", "sub\\file", "bad\0file"})
            assertThrows(IllegalArgumentException.class, () -> ModuleFiles.export(report, directory, name));
    }
    @Test public void missingOversizedAndNonFileReportsAreUnavailable() throws Exception {
        Path directory = files.newFolder("reports").toPath();
        assertThrows(IllegalStateException.class, () -> ModuleFiles.readReport(directory.resolve("missing")));
        assertThrows(IllegalStateException.class, () -> ModuleFiles.readReport(directory));
        Path report = directory.resolve("large");
        Files.write(report, new byte[DiagnosticJournal.MAX_BYTES + 1]);
        assertThrows(IllegalStateException.class, () -> ModuleFiles.readReport(report));
    }
    public String original(String value) { return "instance:" + value; }
    public static String staticOriginal(String value) { return "static:" + value; }
    public static String fail() { throw SENTINEL; }
    private static final RuntimeException SENTINEL = new RuntimeException("native exception");
    private NativeHooks holder(Method method, XposedInterface.Hooker hook) throws Exception {
        var constructor = NativeHooks.class.getDeclaredConstructor(Method.class, XposedInterface.Hooker.class);
        constructor.setAccessible(true);
        NativeHooks holder = constructor.newInstance(method, hook);
        Field backup = NativeHooks.class.getDeclaredField("backup"); backup.setAccessible(true); backup.set(holder, method);
        return holder;
    }
    @Test public void nativeAdapterPreservesInstanceArgumentsAndRebinding() throws Throwable {
        var method = getClass().getMethod("original", String.class);
        var other = new StandaloneTest();
        var holder = holder(method, chain -> {
            assertSame(this, chain.getThisObject()); assertEquals("input", chain.getArg(0));
            return chain.proceedWith(other, new Object[]{"changed"});
        });
        assertEquals("instance:changed", holder.callback(new Object[]{this, "input"}));
    }
    @Test public void nativeAdapterPreservesStaticArgumentsAndOriginalException() throws Throwable {
        var holder = holder(getClass().getMethod("staticOriginal", String.class), chain -> {
            assertNull(chain.getThisObject()); return chain.proceed();
        });
        assertEquals("static:value", holder.callback(new Object[]{"value"}));
        var failing = holder(getClass().getMethod("fail"), XposedInterface.Chain::proceed);
        assertSame(SENTINEL, assertThrows(RuntimeException.class, () -> failing.callback(new Object[]{})));
    }
}
