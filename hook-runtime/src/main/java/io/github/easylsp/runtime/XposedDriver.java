package io.github.easylsp.runtime;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import io.github.easylsp.api.*;
import java.lang.reflect.Member;

public final class XposedDriver implements HookDriver {
    @Override public HookHandle install(Member member, Mode mode, HookCallback callback) {
        XC_MethodHook hook = mode == Mode.REPLACE ? new XC_MethodReplacement() {
            @Override protected Object replaceHookedMethod(MethodHookParam p) throws Throwable {
                HookParam param = wrap(p);
                callback.call(param);
                if (param.getThrowable() != null) throw param.getThrowable();
                if (!param.hasChangedOutcome()) throw new IllegalStateException("replace 回调必须设置结果或异常");
                return param.getResult();
            }
        } : new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                if (mode == Mode.BEFORE) apply(p, callback);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (mode == Mode.AFTER) apply(p, callback);
            }
        };
        XC_MethodHook.Unhook handle = XposedBridge.hookMethod(member, hook);
        return handle::unhook;
    }
    private static HookParam wrap(XC_MethodHook.MethodHookParam p) {
        return new HookParam(p.method, p.thisObject, p.args, p.getResult(), p.getThrowable());
    }
    private static void apply(XC_MethodHook.MethodHookParam p, HookCallback callback) throws Throwable {
        HookParam param = wrap(p);
        callback.call(param);
        if (param.hasChangedOutcome()) {
            if (param.getThrowable() != null) p.setThrowable(param.getThrowable()); else p.setResult(param.getResult());
        }
    }
}
