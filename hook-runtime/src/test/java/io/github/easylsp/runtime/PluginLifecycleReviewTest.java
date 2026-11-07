package io.github.easylsp.runtime;
import io.github.easylsp.api.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.StringReader;
import java.util.concurrent.atomic.AtomicInteger;

public class PluginLifecycleReviewTest {
    public static class Target { public int value() { return 1; } }
    public static class CleanupProbe {
        public int active;
        public int attempts;
        public boolean fail = true;
        public void acquire() { active++; }
        public void release() {
            attempts++;
            if (fail) throw new IllegalStateException("cleanup failed");
            active--;
        }
    }
    private PluginEngine engine(HookDriver driver, Object context, java.util.List<String> states) {
        return new PluginEngine(driver, getClass().getClassLoader(), context, new PluginEngine.Events() {
            public void state(String id,long rev,String instance,String state,String error) { states.add(state); }
            public void log(String id,long rev,String instance,String level,String message,Throwable error) {}
        });
    }
    @Test public void failedOnUnloadCanBeRetriedWithoutInstallingAnotherInstance() throws Throwable {
        CleanupProbe context = new CleanupProbe();
        java.util.List<String> states = new java.util.ArrayList<>();
        PluginEngine engine = engine((member, mode, callback) -> () -> {}, context, states);
        String source = "void onLoad(){context.acquire();} void onUnload(){context.release();}";
        try {
            engine.load("retry-cleanup", 1, new StringReader(source));
            assertThrows(bsh.EvalError.class, () -> engine.unload("retry-cleanup"));
            engine.report("retry-cleanup");
            assertEquals("ERROR", states.get(states.size() - 1));
            assertThrows(IllegalStateException.class, () -> engine.load("retry-cleanup", 1, new StringReader(source)));
            context.fail = false;
            engine.unload("retry-cleanup");
            assertEquals(2, context.attempts);
            assertEquals(0, context.active);
        } finally { context.fail = false; engine.close(); }
    }
    @Test public void retryingFailedUnhookDoesNotRepeatSuccessfulOnUnload() throws Throwable {
        CleanupProbe context = new CleanupProbe(); context.fail = false;
        AtomicInteger attempts = new AtomicInteger();
        PluginEngine engine = engine((member, mode, callback) -> () -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("unhook failed");
        }, context, new java.util.ArrayList<>());
        try {
            engine.load("retry-unhook", 1, new StringReader(
                "void onLoad(){context.acquire(); hook.before(\"" + Target.class.getName() + "\",\"value\",new String[0],new HookCallback(){public void call(HookParam p){}});} void onUnload(){context.release();}"));
            assertThrows(IllegalStateException.class, () -> engine.unload("retry-unhook"));
            engine.unload("retry-unhook");
            assertEquals(2, attempts.get());
            assertEquals(1, context.attempts);
            assertEquals(0, context.active);
        } finally { engine.close(); }
    }
    public static class DeferredCleanup {
        public final java.util.List<Runnable> pending = new java.util.ArrayList<>();
        public final java.util.List<Integer> completed = new java.util.ArrayList<>();
        public void post(Runnable runnable) { pending.add(runnable); }
        public void record(int value) { completed.add(value); }
        public void drain() {
            for (Runnable runnable : java.util.List.copyOf(pending)) runnable.run();
            pending.clear();
        }
    }
    @Test public void deferredUnloadKeepsItsOwnNamespaceAcrossReload() throws Throwable {
        DeferredCleanup context = new DeferredCleanup();
        AtomicInteger hooks = new AtomicInteger();
        PluginEngine engine = new PluginEngine((member, mode, callback) -> {
            hooks.incrementAndGet(); return hooks::decrementAndGet;
        }, getClass().getClassLoader(), context, new PluginEngine.Events() {
            public void state(String id,long rev,String instance,String state,String error) {}
            public void log(String id,long rev,String instance,String level,String message,Throwable error) {}
        });
        String source = "import java.util.concurrent.atomic.AtomicInteger; "
            + "AtomicInteger value = new AtomicInteger(VALUE); "
            + "void finish() { context.record(value.get()); } "
            + "void onLoad() { hook.before(\"" + Target.class.getName() + "\",\"value\",new String[0],new HookCallback(){ public void call(HookParam p) {} }); } "
            + "void onUnload() { context.post(new Runnable(){ public void run(){ AtomicInteger copy = new AtomicInteger(value.get()); finish(); } }); }";
        try {
            engine.load("deferred-unload", 1, new StringReader(source.replace("VALUE", "1")));
            engine.reload("deferred-unload", 2, new StringReader(source.replace("VALUE", "2")));
            assertEquals(1, hooks.get());
            context.drain();
            assertEquals(java.util.List.of(1), context.completed);
            engine.unload("deferred-unload");
            assertEquals(0, hooks.get());
            context.drain();
            assertEquals(java.util.List.of(1, 2), context.completed);
        } finally { engine.close(); }
    }
    @Test public void failedUnhookRetainsOnlyRemainingHandle() throws Throwable {
        AtomicInteger installs = new AtomicInteger(), attempts = new AtomicInteger();
        HookDriver driver = (member, mode, callback) -> { int id = installs.incrementAndGet(); return () -> { attempts.incrementAndGet(); if (id == 2) throw new IllegalStateException("backend"); }; };
        PluginHooks hooks = new PluginHooks(driver, getClass().getClassLoader(), (l,m,e) -> {});
        HookHandle first = hooks.before(Target.class.getName(), "value", new String[0], p -> {});
        first.unhook(); assertEquals(0, hooks.size());
        hooks.before(Target.class.getName(), "value", new String[0], p -> {});
        hooks.before(Target.class.getName(), "value", new String[0], p -> {});
        assertThrows(IllegalStateException.class, hooks::close);
        assertEquals(3, attempts.get()); assertEquals(1, hooks.size());
    }
    @Test public void badReloadPreservesExistingInstance() throws Throwable {
        AtomicInteger unloads = new AtomicInteger();
        HookDriver driver = (member, mode, callback) -> unloads::incrementAndGet;
        PluginEngine engine = new PluginEngine(driver, getClass().getClassLoader(), null, new PluginEngine.Events() {
            public void state(String id,long rev,String instance,String state,String error) {}
            public void log(String id,long rev,String instance,String level,String message,Throwable error) {}
        });
        try {
            engine.load("reload-review", 1, new StringReader("hook.before(\"" + Target.class.getName() + "\",\"value\",new String[0],new io.github.easylsp.api.HookCallback(){ public void call(io.github.easylsp.api.HookParam p) {} });"));
            try { engine.reload("reload-review", 2, new StringReader("void onLoad( {")); fail("expected syntax error"); } catch (bsh.ParseException expected) { assertTrue(expected.getMessage().contains("onLoad")); }
            assertEquals(0, unloads.get()); assertTrue(engine.alreadyLoaded("reload-review", 1));
        } finally { engine.close(); }
        assertEquals(1, unloads.get());
    }
    @Test public void twoModuleEnginesCannotInstallTheSamePlugin() throws Throwable {
        AtomicInteger installed = new AtomicInteger();
        HookDriver driver = (member, mode, callback) -> { installed.incrementAndGet(); return installed::decrementAndGet; };
        PluginEngine.Events events = new PluginEngine.Events() {
            public void state(String id,long rev,String instance,String state,String error) {}
            public void log(String id,long rev,String instance,String level,String message,Throwable error) {}
        };
        PluginEngine manager = new PluginEngine(driver, getClass().getClassLoader(), null, events);
        PluginEngine standalone = new PluginEngine(driver, getClass().getClassLoader(), null, events);
        String script = "hook.before(\"" + Target.class.getName() + "\",\"value\",new String[0],new io.github.easylsp.api.HookCallback(){ public void call(io.github.easylsp.api.HookParam p) {} });";
        try {
            manager.load("module-overlap-review", 1, new StringReader(script));
            try { standalone.load("module-overlap-review", 1, new StringReader(script)); fail("duplicate module"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("另一个模块")); }
            assertEquals(1, installed.get());
            manager.close();
            standalone.load("module-overlap-review", 1, new StringReader(script));
            assertEquals(1, installed.get());
        } finally { manager.close(); standalone.close(); }
        assertEquals(0, installed.get());
    }
}
