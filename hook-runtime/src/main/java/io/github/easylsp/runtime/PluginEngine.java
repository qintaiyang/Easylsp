package io.github.easylsp.runtime;
import bsh.BshMethod;
import bsh.Interpreter;
import io.github.easylsp.api.*;
import java.io.Reader;
import java.util.*;

/** All lifecycle operations are serialized. Hook callbacks still run on target threads. */
public final class PluginEngine {
    public interface Events {
        void state(String pluginId, long revision, String instanceId, String state, String error);
        void log(String pluginId, long revision, String instanceId, String level, String message, Throwable error);
    }
    private record Instance(long revision, String id, Interpreter interpreter, PluginHooks hooks) {}
    private final Map<String, Instance> instances = new HashMap<>();
    private final HookDriver driver;
    private final ClassLoader loader;
    private final Object context;
    private final Events events;
    public PluginEngine(HookDriver driver, ClassLoader loader, Object context, Events events) {
        this.driver = driver; this.loader = loader; this.context = context; this.events = events;
    }
    public synchronized void load(String pluginId, long revision, Reader source) throws Throwable {
        load(pluginId, revision, source, loader);
    }
    public synchronized void load(String pluginId, long revision, Reader source, ClassLoader pluginLoader) throws Throwable {
        Instance current = instances.get(pluginId);
        if (current != null) {
            if (current.revision == revision) { events.state(pluginId, revision, current.id, "RUNNING", ""); return; }
            throw new IllegalStateException("已有运行实例；请显式重新加载");
        }
        String id = UUID.randomUUID().toString();
        PluginLog logger = (level, message, error) -> events.log(pluginId, revision, id, level, message, error);
        PluginHooks hooks = new PluginHooks(driver, pluginLoader, logger);
        Interpreter interpreter = new Interpreter();
        Instance candidate = new Instance(revision, id, interpreter, hooks);
        events.state(pluginId, revision, id, "LOADING", "");
        try {
            interpreter.setClassLoader(new ClassLoader(pluginLoader) {
                @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                    return Class.forName(name, false, PluginEngine.class.getClassLoader());
                }
            });
            interpreter.set("context", context);
            interpreter.set("classLoader", pluginLoader);
            interpreter.set("hook", hooks);
            interpreter.set("reflect", new PluginReflection(pluginLoader));
            interpreter.set("plugin", new PluginEnvironment(pluginId, revision, id, logger));
            interpreter.eval("import io.github.easylsp.api.*;");
            interpreter.eval(source, interpreter.getNameSpace(), "main.java");
            invoke(candidate, "onLoad");
            instances.put(pluginId, candidate);
            events.state(pluginId, revision, id, "RUNNING", "");
        } catch (Throwable error) {
            hooks.close();
            interpreter.getNameSpace().clear();
            events.state(pluginId, revision, id, "ERROR", error.toString());
            logger.write("ERROR", "加载失败", error);
            throw error;
        }
    }
    public synchronized void unload(String pluginId) throws Throwable {
        Instance current = instances.remove(pluginId);
        if (current == null) { events.state(pluginId, 0, "", "STOPPED", ""); return; }
        Throwable failure = null;
        try { invoke(current, "onUnload"); }
        catch (Throwable error) { failure = error; events.log(pluginId, current.revision, current.id, "ERROR", "onUnload 失败", error); }
        finally { current.hooks.close(); current.interpreter.getNameSpace().clear(); }
        events.state(pluginId, current.revision, current.id, failure == null ? "STOPPED" : "ERROR", failure == null ? "" : failure.toString());
        if (failure != null) throw failure;
    }
    public synchronized void reload(String pluginId, long revision, Reader source) throws Throwable {
        unload(pluginId);
        load(pluginId, revision, source);
    }
    public synchronized void reload(String pluginId, long revision, Reader source, ClassLoader pluginLoader) throws Throwable {
        unload(pluginId); load(pluginId, revision, source, pluginLoader);
    }
    public synchronized void report(String pluginId) {
        Instance current = instances.get(pluginId);
        events.state(pluginId, current == null ? 0 : current.revision, current == null ? "" : current.id, current == null ? "STOPPED" : "RUNNING", "");
    }
    public synchronized void close() {
        for (String pluginId : new ArrayList<>(instances.keySet())) {
            try { unload(pluginId); }
            catch (Throwable error) { /* unload already reports its error and releases hooks. */ }
        }
    }
    private void invoke(Instance instance, String name) throws Throwable {
        BshMethod method = instance.interpreter.getNameSpace().getMethod(name, new Class<?>[0]);
        if (method != null) method.invoke(new Object[0], instance.interpreter);
    }
}
