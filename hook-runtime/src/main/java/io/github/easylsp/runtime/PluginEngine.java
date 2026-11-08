package io.github.easylsp.runtime;
import bsh.BshMethod;
import bsh.Interpreter;
import bsh.Parser;
import bsh.ParseException;
import bsh.NameSpace;
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
    private static final class Instance {
        final long revision;
        final String id;
        final Interpreter interpreter;
        final PluginHooks hooks;
        final PluginProgram program;
        final PluginEnvironment environment;
        final List<ComponentInstance> components = new ArrayList<>();
        boolean unloadCompleted;
        Instance(long revision, String id, Interpreter interpreter, PluginHooks hooks, PluginProgram program, PluginEnvironment environment) {
            this.revision = revision; this.id = id; this.interpreter = interpreter; this.hooks = hooks;
            this.program = program; this.environment = environment;
        }
    }
    private static final class ComponentInstance {
        final NameSpace namespace;
        final PluginComponent api;
        final PluginHooks hooks;
        boolean unloaded;
        ComponentInstance(NameSpace namespace, PluginComponent api, PluginHooks hooks) { this.namespace = namespace; this.api = api; this.hooks = hooks; }
    }
    private final Map<String, Instance> instances = new HashMap<>();
    private final PluginServices services = new PluginServices();
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
        load(pluginId, revision, PluginProgram.single(source), pluginLoader);
    }
    public synchronized void load(String pluginId, long revision, PluginProgram program, ClassLoader pluginLoader) throws Throwable {
        program.validate();
        Instance current = instances.get(pluginId);
        if (current != null) {
            if (current.revision == revision && !cleanupFailures.containsKey(pluginId)) { report(pluginId); return; }
            throw new IllegalStateException("已有运行实例；请显式重新加载");
        }
        PluginServices.Client client = services.client(pluginId, program);
        synchronized (System.getProperties()) {
            String key = "io.github.easylsp.active-plugin." + pluginId;
            if (System.getProperties().containsKey(key)) throw new IllegalStateException("同一插件已由另一个模块加载；停用原模块并重启目标后切换");
            System.getProperties().put(key, this);
        }
        String id = UUID.randomUUID().toString();
        PluginLog logger = (level, message, error) -> events.log(pluginId, revision, id, level, message, error);
        PluginHooks hooks = new PluginHooks(driver, pluginLoader, logger);
        Interpreter interpreter = new Interpreter();
        PluginEnvironment environment = new PluginEnvironment(pluginId, revision, id, logger, program, client);
        Instance candidate = new Instance(revision, id, interpreter, hooks, program, environment);
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
            interpreter.set("plugin", environment);
            interpreter.eval("import io.github.easylsp.api.*;");
            NameSpace api = interpreter.getNameSpace();
            for (PluginProgram.Component definition : program.components) {
                NameSpace namespace = new NameSpace(api, definition.id);
                PluginComponent component = new PluginComponent(definition, environment);
                PluginHooks componentHooks = new PluginHooks(driver, pluginLoader, logger);
                namespace.setTypedVariable("component", PluginComponent.class, component, false);
                namespace.setTypedVariable("hook", PluginHooks.class, componentHooks, false);
                ComponentInstance instance = new ComponentInstance(namespace, component, componentHooks);
                candidate.components.add(instance);
                Object exports = interpreter.eval(new StringReader(program.text(definition.path)), namespace, definition.path);
                if (!(exports instanceof bsh.This)) throw new IllegalArgumentException(definition.path + " must return this");
                environment.components.put(definition.id, exports);
            }
            // Components inherit only host bindings, not entry script variables.
            interpreter.setNameSpace(new NameSpace(api, "main.java"));
            interpreter.eval(new StringReader(program.text("main.java")), interpreter.getNameSpace(), "main.java");
            for (ComponentInstance component : candidate.components) invoke(candidate, component.namespace, "onLoad");
            invoke(candidate, "onLoad");
            client.ready();
            instances.put(pluginId, candidate);
            events.state(pluginId, revision, id, "RUNNING", "");
        } catch (Throwable error) {
            boolean cleaned = true;
            try { client.close(); cleanup(candidate); } catch (Throwable cleanup) { error.addSuppressed(cleanup); cleaned = false; }
            if (cleaned) { System.getProperties().remove("io.github.easylsp.active-plugin." + pluginId, this); }
            else { instances.put(pluginId, candidate); cleanupFailures.put(pluginId, error.toString()); }
            events.state(pluginId, revision, id, "ERROR", error.toString());
            logger.write("ERROR", "加载失败", error);
            throw error;
        }
    }
    public synchronized void unload(String pluginId) throws Throwable {
        Instance current = instances.get(pluginId);
        if (current == null) { events.state(pluginId, 0, "", "STOPPED", ""); return; }
        for (Map.Entry<String, Instance> other : instances.entrySet()) {
            if (!other.getKey().equals(pluginId) && !Collections.disjoint(other.getValue().program.requires.keySet(), current.program.provides.keySet()))
                throw new IllegalStateException("Unload dependent plugin first: " + other.getKey());
        }
        current.environment.services.close();
        Throwable failure = null;
        try {
            if (!current.unloadCompleted) {
                invoke(current, "onUnload");
                current.unloadCompleted = true;
            }
        }
        catch (Throwable error) { failure = error; events.log(pluginId, current.revision, current.id, "ERROR", "onUnload 失败", error); }
        try { cleanup(current); }
        catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        if (current.hooks.size() == 0 && failure == null) {
            // onUnload may post cleanup to another thread. Its callbacks still own the
            // namespace; let GC release it after those references are gone.
            instances.remove(pluginId); System.getProperties().remove("io.github.easylsp.active-plugin." + pluginId, this); cleanupFailures.remove(pluginId);
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
    public synchronized void reload(String pluginId, long revision, PluginProgram program, ClassLoader pluginLoader) throws Throwable {
        program.validate();
        unload(pluginId); load(pluginId, revision, program, pluginLoader);
    }
    public synchronized String metrics(String id, Boolean enabled) {
        Instance current = instances.get(id);
        if (current == null) throw new IllegalArgumentException("插件没有运行实例");
        List<PluginHooks> hooks = new ArrayList<>();
        hooks.add(current.hooks);
        for (ComponentInstance component : current.components) hooks.add(component.hooks);
        if (enabled != null) for (PluginHooks item : hooks) item.metrics(enabled);
        try {
            org.json.JSONObject result = new org.json.JSONObject(current.hooks.metrics());
            for (int i = 1; i < hooks.size(); i++) {
                org.json.JSONObject component = new org.json.JSONObject(hooks.get(i).metrics());
                for (String key : new String[]{"installed", "active", "hits", "exceptions"})
                    result.put(key, result.getLong(key) + component.getLong(key));
            }
            return result.toString();
        } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
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
        for (String id : reverseOrder()) {
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
        for (String pluginId : reverseOrder()) {
            try { unload(pluginId); }
            catch (Throwable error) { /* unload already reports its error and releases hooks. */ }
        }
    }
    private void invoke(Instance instance, String name) throws Throwable {
        invoke(instance, instance.interpreter.getNameSpace(), name);
    }
    private void invoke(Instance instance, NameSpace namespace, String name) throws Throwable {
        BshMethod method = namespace.getMethod(name, new Class<?>[0], true);
        if (method != null) method.invoke(new Object[0], instance.interpreter);
    }
    private List<String> reverseOrder() {
        Map<String, PluginProgram> programs = new LinkedHashMap<>();
        instances.forEach((id, instance) -> programs.put(id, instance.program));
        List<String> order = PluginProgram.order(programs); Collections.reverse(order); return order;
    }
    private void cleanup(Instance instance) throws Throwable {
        Throwable failure = null;
        try { instance.environment.resources.close(); } catch (Throwable e) { failure = e; }
        for (int i = instance.components.size() - 1; i >= 0; i--) {
            ComponentInstance c = instance.components.get(i);
            try { if (!c.unloaded) { invoke(instance, c.namespace, "onUnload"); c.unloaded = true; } }
            catch (Throwable e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            try { c.api.resources.close(); } catch (Throwable e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            try { c.hooks.close(); } catch (Throwable e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        try { instance.hooks.close(); } catch (Throwable e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        if (failure != null) throw failure;
    }
}
