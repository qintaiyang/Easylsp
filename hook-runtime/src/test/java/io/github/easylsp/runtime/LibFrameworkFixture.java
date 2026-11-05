package io.github.easylsp.runtime;

import io.github.libxposed.api.XposedInterface;
import io.github.easylsp.api.*;
import java.lang.reflect.*;
import java.util.*;

/** A chain simulator: the production API 101-compatible adapter runs over this framework boundary. */
final class LibFrameworkFixture {
    private record Entry(Member member, XposedInterface.Hooker hooker) { }
    private final List<Entry> entries = new ArrayList<>();
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }
    final XposedInterface framework = proxy(XposedInterface.class, (object, method, args) -> {
        if (!method.getName().equals("hook")) throw new UnsupportedOperationException(method.getName());
        Member member = (Member) args[0];
        return proxy(XposedInterface.HookBuilder.class, (builder, setting, values) -> {
            if (!setting.getName().equals("intercept")) return builder;
            Entry entry = new Entry(member, (XposedInterface.Hooker) values[0]); entries.add(entry);
            return proxy(XposedInterface.HookHandle.class, (handle, action, ignored) -> {
                if (action.getName().equals("unhook")) { entries.remove(entry); return null; }
                throw new UnsupportedOperationException(action.getName());
            });
        });
    });
    Object call(Method method, Object receiver, Object[] args) throws Throwable {
        List<Entry> matching = entries.stream().filter(it -> it.member.equals(method)).toList();
        return proceed(matching, 0, method, receiver, args);
    }
    private Object proceed(List<Entry> matching, int index, Method method, Object receiver, Object[] args) throws Throwable {
        if (index == matching.size()) {
            try { return method.invoke(receiver, args); } catch (InvocationTargetException error) { throw error.getCause(); }
        }
        XposedInterface.Chain chain = proxy(XposedInterface.Chain.class, (object, access, values) -> switch (access.getName()) {
            case "getExecutable" -> method;
            case "getThisObject" -> receiver;
            case "getArgs" -> Collections.unmodifiableList(Arrays.asList(args));
            case "getArg" -> args[(int) values[0]];
            case "proceed" -> proceed(matching, index + 1, method, receiver, values == null || values.length == 0 ? args : (Object[]) values[0]);
            default -> throw new UnsupportedOperationException(access.getName());
        });
        return matching.get(index).hooker.intercept(chain);
    }
}
