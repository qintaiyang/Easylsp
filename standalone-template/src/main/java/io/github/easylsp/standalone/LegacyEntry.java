package io.github.easylsp.standalone;
import android.app.*;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.easylsp.runtime.XposedDriver;

public final class LegacyEntry implements IXposedHookZygoteInit, IXposedHookLoadPackage {
    private String modulePath;
    public void initZygote(StartupParam startup) { modulePath = startup.modulePath; }
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam info) throws Throwable {
        var config = StandaloneRuntime.config(modulePath);
        if (!info.isFirstApplication || !info.packageName.equals(config.getString("packageName")) || !info.processName.equals(config.getString("processName"))) return;
        XposedHelpers.findAndHookMethod(Instrumentation.class, "callApplicationOnCreate", Application.class, new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam param) { StandaloneRuntime.start(modulePath, (Application) param.args[0], new XposedDriver()); }
        });
    }
}
