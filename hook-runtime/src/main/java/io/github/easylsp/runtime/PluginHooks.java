package io.github.easylsp.runtime;
import io.github.easylsp.api.*;
import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.List;

public final class PluginHooks implements AutoCloseable {
    private final HookDriver driver;
    private final ClassLoader loader;
    private final PluginLog log;
    private final List<HookHandle> handles = new ArrayList<>();
    private boolean closed;
    public PluginHooks(HookDriver driver, ClassLoader loader, PluginLog log) {
        this.driver = driver; this.loader = loader; this.log = log;
    }
    public HookHandle before(String owner, String name, String[] parameters, HookCallback callback) throws Throwable {
        return install(owner, name, parameters, HookDriver.Mode.BEFORE, callback);
    }
    public HookHandle after(String owner, String name, String[] parameters, HookCallback callback) throws Throwable {
        return install(owner, name, parameters, HookDriver.Mode.AFTER, callback);
    }
    public HookHandle replace(String owner, String name, String[] parameters, HookCallback callback) throws Throwable {
        return install(owner, name, parameters, HookDriver.Mode.REPLACE, callback);
    }
    private synchronized HookHandle install(String owner, String name, String[] parameters, HookDriver.Mode mode, HookCallback callback) throws Throwable {
        if (closed) throw new IllegalStateException("插件已卸载，不能再安装 Hook");
        Class<?> type = Class.forName(owner, false, loader);
        Class<?>[] types = new Class<?>[parameters.length];
        for (int i = 0; i < types.length; i++) types[i] = resolve(parameters[i]);
        Member member = name.equals("<init>") ? type.getDeclaredConstructor(types) : type.getDeclaredMethod(name, types);
        HookHandle delegate = driver.install(member, mode, param -> {
            try { callback.call(param); }
            catch (Throwable error) { log.write("ERROR", "Hook 回调失败: " + member, error); throw error; }
        });
        HookHandle handle = new HookHandle() {
            private boolean released;
            public synchronized void unhook() { if (!released) { delegate.unhook(); released = true; } }
        };
        handles.add(handle);
        return handle;
    }
    private Class<?> resolve(String name) throws ClassNotFoundException {
        return switch (name) {
            case "boolean" -> boolean.class; case "byte" -> byte.class; case "char" -> char.class;
            case "short" -> short.class; case "int" -> int.class; case "long" -> long.class;
            case "float" -> float.class; case "double" -> double.class;
            default -> name.endsWith("[]") ? Array.newInstance(resolve(name.substring(0, name.length() - 2)), 0).getClass() : Class.forName(name, false, loader);
        };
    }
    public synchronized int size() { return handles.size(); }
    @Override public synchronized void close() {
        closed = true;
        for (int i = handles.size() - 1; i >= 0; i--) handles.get(i).unhook();
        handles.clear();
    }
}
