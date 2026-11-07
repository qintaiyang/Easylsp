package io.github.easylsp.runtime;
import bsh.BshMethod;
import bsh.Interpreter;
import bsh.Parser;
import bsh.ParseException;
import java.io.StringReader;
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
    private final Map<String, String> cleanupFailures = new HashMap<>();
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
            if (current.revision == revision && !cleanupFailures.containsKey(pluginId)) { report(pluginId); return; }
            throw new IllegalStateException("已有运行实例；请显式重新加载");
        }
        synchronized (System.getProperties()) {
            String key = "io.github.easylsp.active-plugin." + pluginId;
            if (System.getProperties().containsKey(key)) throw new IllegalStateException("同一插件已由另一个模块加载；停用原模块并重启目标后切换");
            System.getProperties().put(key, this);
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
            try { hooks.close(); } catch (Throwable cleanup) { error.addSuppressed(cleanup); }
            if (hooks.size() == 0) { interpreter.getNameSpace().clear(); System.getProperties().remove("io.github.easylsp.active-plugin." + pluginId, this); }
            else { instances.put(pluginId, candidate); cleanupFailures.put(pluginId, error.toString()); }
            events.state(pluginId, revision, id, "ERROR", error.toString());
            logger.write("ERROR", "加载失败", error);
            throw error;
        }
    }
    public synchronized void unload(String pluginId) throws Throwable {
        Instance current = instances.get(pluginId);
        if (current == null) { events.state(pluginId, 0, "", "STOPPED", ""); return; }
        Throwable failure = null;
        try { if (!cleanupFailures.containsKey(pluginId)) invoke(current, "onUnload"); }
        catch (Throwable error) { failure = error; events.log(pluginId, current.revision, current.id, "ERROR", "onUnload 失败", error); }
        try { current.hooks.close(); }
        catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        if (current.hooks.size() == 0) {
            instances.remove(pluginId); System.getProperties().remove("io.github.easylsp.active-plugin." + pluginId, this); cleanupFailures.remove(pluginId); current.interpreter.getNameSpace().clear();
        } else cleanupFailures.put(pluginId, failure.toString());
        events.state(pluginId, current.revision, current.id, failure == null ? "STOPPED" : "ERROR", failure == null ? "" : failure.toString());
        if (failure != null) throw failure;
    }
    public synchronized void reload(String pluginId, long revision, Reader source) throws Throwable {
        reload(pluginId, revision, source, loader);
    }
    public synchronized void reload(String pluginId, long revision, Reader source, ClassLoader pluginLoader) throws Throwable {
        String text = readAndParse(source);
        unload(pluginId); load(pluginId, revision, new StringReader(text), pluginLoader);
    }
    public synchronized String metrics(String id, Boolean enabled) {
        Instance current = instances.get(id);
        if (current == null) throw new IllegalArgumentException("插件没有运行实例");
        if (enabled != null) current.hooks.metrics(enabled);
        return current.hooks.metrics();
    }
    public synchronized void report(String pluginId) {
        Instance current = instances.get(pluginId);
        events.state(pluginId, current == null ? 0 : current.revision, current == null ? "" : current.id, current == null ? "STOPPED" : cleanupFailures.containsKey(pluginId) ? "ERROR" : "RUNNING", cleanupFailures.getOrDefault(pluginId, ""));
    }
    public synchronized boolean alreadyLoaded(String pluginId, long revision) {
        Instance current = instances.get(pluginId);
        if (current == null) return false;
        report(pluginId);
        if (current.revision != revision || cleanupFailures.containsKey(pluginId))
            throw new IllegalStateException("已有实例 r" + current.revision + "；请显式重新加载或清理残留 Hook");
        return true;
    }
    public synchronized void synchronizeEnabled(Set<String> enabled) {
        for (String id : new ArrayList<>(instances.keySet())) {
            if (enabled.contains(id)) report(id);
            else try { unload(id); } catch (Throwable error) { events.log(id, 0, "", "ERROR", "同步卸载失败", error); }
        }
    }
    public static String readAndParse(Reader source) throws java.io.IOException, ParseException {
        StringBuilder text = new StringBuilder(); char[] buffer = new char[4096]; int n;
        while ((n = source.read(buffer)) != -1) text.append(buffer, 0, n);
        Parser parser = new Parser(new StringReader(text.toString()));
        try { while (!parser.Line()) parser.popNode(); }
        catch (ParseException error) {
            int line = error.getErrorLineNumber();
            String[] lines = text.toString().split("\\R", -1);
            StringBuilder nearby = new StringBuilder(error.getMessage());
            for (int i = Math.max(0, line - 2); i < Math.min(lines.length, line + 1); i++) nearby.append("\n").append(i + 1).append(": ").append(lines[i]);
            throw new ParseException(nearby.toString());
        }
        return text.toString();
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
