package io.github.easylsp.runtime;
import io.github.easylsp.api.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.StringReader;
import java.util.concurrent.atomic.AtomicInteger;

public class PluginLifecycleReviewTest {
    public static class Target { public int value() { return 1; } }
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
