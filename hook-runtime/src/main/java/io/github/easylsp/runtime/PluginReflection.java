package io.github.easylsp.runtime;
import java.lang.reflect.*;
/** Target-loader reflection helpers, exposed as `reflect` in API v1 plugin scripts. */
public final class PluginReflection {
    private final ClassLoader loader;
    private record MemberKey(Class<?> owner, String name, java.util.List<Class<?>> parameters) {}
    private final java.util.Map<MemberKey, AccessibleObject> members = new java.util.HashMap<>();
    public java.util.Map<String, Object> inspect(Object receiver) throws IllegalAccessException {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("class", receiver.getClass().getName());
        for (Class<?> type = receiver.getClass(); type != null && result.size() < 65; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || result.size() >= 65) continue;
                field.setAccessible(true); Object value = field.get(receiver);
                result.put(type.getName() + "." + field.getName(), value == null || value instanceof Number || value instanceof Boolean || value instanceof String ? value : "<" + value.getClass().getName() + ">");
            }
        }
        return result;
    }
    public PluginReflection(ClassLoader loader) { this.loader = loader; }
    public Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }
    public Field field(String owner, String name) throws ReflectiveOperationException {
        return field(type(owner), name);
    }
    private synchronized Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        MemberKey key = new MemberKey(owner, name, java.util.List.of());
        if (members.containsKey(key)) return (Field) members.get(key);
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); members.put(key, field); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(owner + "." + name);
    }
    public Object get(Object receiver, String name) throws ReflectiveOperationException { return field(receiver.getClass(), name).get(receiver); }
    public void set(Object receiver, String name, Object value) throws ReflectiveOperationException { field(receiver.getClass(), name).set(receiver, value); }
    public Method method(String owner, String name, Class<?>[] parameters) throws ReflectiveOperationException { return method(type(owner), name, parameters); }
    private synchronized Method method(Class<?> owner, String name, Class<?>[] parameters) throws ReflectiveOperationException {
        MemberKey key = new MemberKey(owner, name + "()", java.util.List.of(parameters));
        if (members.containsKey(key)) return (Method) members.get(key);
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try { Method method = type.getDeclaredMethod(name, parameters); method.setAccessible(true); members.put(key, method); return method; }
            catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }
    public Object call(Object receiver, String name, Class<?>[] parameters, Object[] arguments) throws ReflectiveOperationException { return method(receiver.getClass(), name, parameters).invoke(receiver, arguments); }
}
