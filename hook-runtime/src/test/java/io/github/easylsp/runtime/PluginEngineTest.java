package io.github.easylsp.runtime;
import io.github.easylsp.api.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

@RunWith(Parameterized.class)
public class PluginEngineTest {
    @Parameterized.Parameters(name = "{0}") public static Object[] drivers() { return new Object[] {"reference", "libxposed"}; }
    public PluginEngineTest(String backend) { driver.backend = backend; }
    public static class Calculator {
        public int add(int a, int b) { return a + b; }
        public String add(String a, String b) { return a + b; }
        public static int multiply(int a, int b) { return a * b; }
        public int fail() { throw new IllegalStateException("target failure"); }
    }
    private static class TestDriver implements HookDriver {
        record Entry(Member member, Mode mode, HookCallback callback) {}
        final List<Entry> hooks = new ArrayList<>();
        String backend;
        final LibFrameworkFixture lib = new LibFrameworkFixture();
        public HookHandle install(Member member, Mode mode, HookCallback callback) throws Throwable {
            Entry entry = new Entry(member, mode, callback); hooks.add(entry);
            HookHandle real = "libxposed".equals(backend) ? new LibXposedDriver(lib.framework).install(member, mode, callback) : () -> {};
            return () -> { real.unhook(); hooks.remove(entry); };
        }
        Object call(String name, Class<?>[] types, Object... args) throws Throwable {
            Method method = Calculator.class.getDeclaredMethod(name, types);
            if ("libxposed".equals(backend)) return lib.call(method, new Calculator(), args);
            HookParam param = new HookParam(method, new Calculator(), args, null, null);
            List<Entry> matching = hooks.stream().filter(it -> it.member.equals(method)).toList();
            for (Entry it : matching) if (it.mode == Mode.BEFORE) it.callback.call(param);
            Entry replacement = matching.stream().filter(it -> it.mode == Mode.REPLACE).findFirst().orElse(null);
            if (replacement != null) replacement.callback.call(param);
            else if (!param.hasChangedOutcome()) {
                try { param = new HookParam(method, param.thisObject, args, method.invoke(param.thisObject, args), null); }
                catch (InvocationTargetException error) { param = new HookParam(method, param.thisObject, args, null, error.getCause()); }
            }
            for (Entry it : matching) if (it.mode == Mode.AFTER) it.callback.call(param);
            if (param.getThrowable() != null) throw param.getThrowable();
            return param.getResult();
        }
        Object add() throws Throwable { return call("add", new Class<?>[] {int.class, int.class}, 2, 3); }
    }
    private final TestDriver driver = new TestDriver();
    private final List<String> states = new ArrayList<>();
    private final List<String> logs = new ArrayList<>();
    private final PluginEngine engine = new PluginEngine(driver, getClass().getClassLoader(), new Object(), new PluginEngine.Events() {
        public void state(String id, long revision, String instance, String state, String error) { states.add(state + ":" + revision); }
        public void log(String id, long revision, String instance, String level, String message, Throwable error) { logs.add(level + ":" + message); }
    });
    private String script(String mode, String body) {
        return "void onLoad() { hook." + mode + "(\"" + Calculator.class.getName() + "\", \"add\", new String[] {\"int\",\"int\"}, new HookCallback() { public void call(HookParam p) { " + body + " } }); } void onUnload() { plugin.log(\"unloaded\"); }";
    }
    private void load(long revision, String script) throws Throwable { engine.load("demo", revision, new StringReader(script)); }
    @Test public void beforeChangesArgumentAndUnloads() throws Throwable {
        assertEquals(5, driver.add()); load(1, script("before", "p.args[0] = Integer.valueOf(4);")); assertEquals(7, driver.add());
        engine.unload("demo"); assertEquals(5, driver.add()); assertTrue(logs.contains("INFO:unloaded"));
    }
    @Test public void afterReplacesResultAndUnloads() throws Throwable { load(1, script("after", "p.setResult(Integer.valueOf(11));")); assertEquals(11, driver.add()); engine.unload("demo"); assertEquals(5, driver.add()); }
    @Test public void replaceSkipsOriginalAndUnloads() throws Throwable { load(1, script("replace", "p.setResult(Integer.valueOf(42));")); assertEquals(42, driver.add()); engine.unload("demo"); assertEquals(5, driver.add()); }
    @Test public void duplicateRevisionDoesNotReinstallAndNewRevisionNeedsReload() throws Throwable {
        String source = script("after", "p.setResult(Integer.valueOf(11));"); load(1, source); load(1, source); assertEquals(1, driver.hooks.size());
        try { load(2, source); fail("must require reload"); } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("重新加载")); }
        assertEquals(11, driver.add()); engine.reload("demo", 2, new StringReader(script("replace", "p.setResult(Integer.valueOf(42));"))); assertEquals(42, driver.add()); assertEquals(1, driver.hooks.size());
    }
    @Test public void failureRemovesPartialHooks() throws Throwable {
        String source = script("before", "") + "\nthrow new RuntimeException(\"broken\");";
        // Install a hook before raising an evaluation error, outside onLoad.
        source = source.replace("void onLoad() {", "void register() {").replace("\nthrow", "\nregister(); throw");
        try { load(1, source); fail("must report script error"); } catch (bsh.EvalError expected) { assertEquals(0, driver.hooks.size()); assertTrue(states.contains("ERROR:1")); }
    }
    @Test public void failingOnUnloadStillReleasesHandles() throws Throwable {
        String source = script("after", "p.setResult(Integer.valueOf(11));").replace("plugin.log(\"unloaded\");", "throw new RuntimeException(\"unload failed\");");
        load(1, source); try { engine.unload("demo"); fail("must surface failure"); } catch (bsh.EvalError expected) { assertEquals(5, driver.add()); assertEquals(0, driver.hooks.size()); }
    }
    @Test public void overloadResolutionIsExact() throws Throwable {
        load(1, script("replace", "p.setResult(Integer.valueOf(42));"));
        assertEquals("ab", driver.call("add", new Class<?>[] {String.class, String.class}, "a", "b")); assertEquals(42, driver.add());
    }
    @Test public void targetExceptionIsPreserved() throws Throwable {
        try { driver.call("fail", new Class<?>[0]); fail("must throw target exception"); } catch (IllegalStateException expected) { assertEquals("target failure", expected.getMessage()); }
    }
    @Test public void scriptCallbackFailureIsLogged() throws Throwable {
        load(1, script("before", "throw new RuntimeException(\"callback failed\");"));
        try { driver.add(); fail("must propagate callback failure"); } catch (Throwable expected) { assertTrue(logs.stream().anyMatch(it -> it.contains("Hook 回调失败"))); }
    }
    @Test public void unloadingPreventsNewRegistrations() throws Throwable {
        PluginHooks hooks = new PluginHooks(driver, getClass().getClassLoader(), (level, message, error) -> {}); hooks.close();
        try { hooks.before(Calculator.class.getName(), "add", new String[] {"int","int"}, p -> {}); fail("must reject after unload"); } catch (IllegalStateException expected) { assertEquals(0, driver.hooks.size()); }
    }
    @Test public void bundledExamplesRunWithIsolatedTargetLoader() throws Throwable {
        ClassLoader target = new ClassLoader(null) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals(Calculator.class.getName())) return Calculator.class;
                throw new ClassNotFoundException(name);
            }
        };
        PluginEngine isolated = new PluginEngine(driver, target, new Object(), new PluginEngine.Events() {
            public void state(String id, long revision, String instance, String state, String error) {}
            public void log(String id, long revision, String instance, String level, String message, Throwable error) {}
        });
        String[] names = {"before-add", "after-add", "replace-add"}; int[] results = {7, 11, 42};
        for (int i = 0; i < names.length; i++) {
            try (InputStream input = new FileInputStream(new File(new File(System.getProperty("easylsp.examples"), names[i]), "main.java"))) {
                String source = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replace("io.github.easylsp.demo.Calculator", Calculator.class.getName());
                isolated.load(names[i], 1, new StringReader(source)); assertEquals(results[i], driver.add());
                isolated.unload(names[i]); assertEquals(5, driver.add());
            }
        }
    }

    @Test public void beforeCanExplicitlySkipOriginal() throws Throwable {
        load(1, script("before", "p.skipWith(Integer.valueOf(42));")); assertEquals(42, driver.add());
        engine.unload("demo"); assertEquals(5, driver.add());
    }
    @Test public void afterCanObserveAndReplaceTargetException() throws Throwable {
        String source = "void onLoad() { hook.after(\"" + Calculator.class.getName() + "\", \"fail\", new String[0], new HookCallback() { public void call(HookParam p) { if (p.getThrowable() == null) throw new RuntimeException(\"missing target exception\"); p.setResult(Integer.valueOf(9)); } }); }";
        load(1, source); assertEquals(9, driver.call("fail", new Class<?>[0])); engine.unload("demo");
    }
    @Test public void constructorAndExplicitHandleUseExactMember() throws Throwable {
        PluginHooks hooks = new PluginHooks(driver, getClass().getClassLoader(), (level, message, error) -> {});
        HookHandle handle = hooks.before(Calculator.class.getName(), "<init>", new String[0], p -> {});
        assertTrue(driver.hooks.get(0).member instanceof Constructor<?>); handle.unhook(); handle.unhook();
        assertEquals(0, driver.hooks.size()); hooks.close();
    }
    @Test public void staticMethodUsesSameSignatureApi() throws Throwable {
        String source = "void onLoad() { hook.replace(\"" + Calculator.class.getName() + "\", \"multiply\", new String[] {\"int\",\"int\"}, new HookCallback() { public void call(HookParam p) { p.setResult(Integer.valueOf(99)); } }); }";
        load(1, source); assertEquals(99, driver.call("multiply", new Class<?>[] {int.class, int.class}, 2, 3));
        engine.unload("demo"); assertEquals(6, driver.call("multiply", new Class<?>[] {int.class, int.class}, 2, 3));
    }

}
