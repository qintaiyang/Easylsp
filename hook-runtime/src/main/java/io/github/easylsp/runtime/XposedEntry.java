package io.github.easylsp.runtime;
import android.app.Application;
import android.app.Instrumentation;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.easylsp.api.internal.Protocol;

public final class XposedEntry implements IXposedHookLoadPackage {
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam packageInfo) throws Throwable {
        if (packageInfo.packageName.equals(Protocol.MANAGER_PACKAGE)) {
            XposedHelpers.findAndHookMethod(ModuleStatus.class.getName(), packageInfo.classLoader, "isActive", XC_MethodReplacement.returnConstant(true));
            XposedHelpers.findAndHookMethod(ModuleStatus.class.getName(), packageInfo.classLoader, "frameworkVersion", XC_MethodReplacement.returnConstant("Xposed API " + XposedBridge.getXposedVersion()));
            return;
        }
        // Initialize each application process once.
        if (!packageInfo.isFirstApplication) return;
        XposedHelpers.findAndHookMethod(Instrumentation.class, "callApplicationOnCreate", Application.class, new XC_MethodHook() {
            private boolean initialized;
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (initialized) return;
                initialized = true;
                Application app = (Application) param.args[0];
                RuntimeBootstrap.start(app, new XposedDriver());
            }
        });
    }
}
