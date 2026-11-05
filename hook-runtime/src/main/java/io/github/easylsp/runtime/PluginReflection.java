package io.github.easylsp.runtime;
import java.lang.reflect.*;
/** Target-loader reflection helpers, exposed as `reflect` in API v1 plugin scripts. */
public final class PluginReflection {
    private final ClassLoader loader;
    public PluginReflection(ClassLoader loader) { this.loader = loader; }
    public Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }
    public Field field(String owner, String name) throws ReflectiveOperationException {
        for (Class<?> type = type(owner); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(owner + "." + name);
    }
    public Object get(Object receiver, String name) throws ReflectiveOperationException { return field(receiver.getClass().getName(), name).get(receiver); }
    public void set(Object receiver, String name, Object value) throws ReflectiveOperationException { field(receiver.getClass().getName(), name).set(receiver, value); }
    public Method method(String owner, String name, Class<?>[] parameters) throws ReflectiveOperationException { Method method = type(owner).getDeclaredMethod(name, parameters); method.setAccessible(true); return method; }
    public Object call(Object receiver, String name, Class<?>[] parameters, Object[] arguments) throws ReflectiveOperationException { return method(receiver.getClass().getName(), name, parameters).invoke(receiver, arguments); }
}
