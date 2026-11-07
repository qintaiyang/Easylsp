package io.github.easylsp.runtime;
import io.github.libxposed.api.XposedInterface;
import io.github.easylsp.api.*;
import java.lang.reflect.*;

public final class LibXposedDriver implements HookDriver {
    private final XposedInterface framework;
    public LibXposedDriver(XposedInterface framework) { this.framework = framework; }
    @Override public HookHandle install(Member member, Mode mode, HookCallback callback) {
        var handle = framework.hook((Executable) member).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
            Object[] args = chain.getArgs().toArray();
            if (mode != Mode.AFTER) {
                HookParam param = new HookParam(member, chain.getThisObject(), args, null, null);
                callback.call(param);
                if (mode == Mode.REPLACE && !param.hasChangedOutcome()) throw new IllegalStateException("replace 回调必须设置结果或异常");
                if (param.hasChangedOutcome()) return outcome(param);
                return chain.proceed(args);
            }
            Object result = null; Throwable failure = null;
            try { result = chain.proceed(args); } catch (Throwable error) { failure = error; }
            HookParam param = new HookParam(member, chain.getThisObject(), args, result, failure);
            callback.call(param);
            return outcome(param);
        });
        return handle::unhook;
    }
    private static Object outcome(HookParam param) throws Throwable {
        if (param.getThrowable() != null) throw param.getThrowable();
        return param.getResult();
    }
}
