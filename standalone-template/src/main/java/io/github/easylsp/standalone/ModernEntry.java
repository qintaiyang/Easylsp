package io.github.easylsp.standalone;
import android.app.*;
import io.github.libxposed.api.XposedModule;
import io.github.easylsp.api.HookDriver;
import io.github.easylsp.runtime.LibXposedDriver;

public final class ModernEntry extends XposedModule {
    public void onPackageReady(PackageReadyParam info) {
        try {
            String apk = getModuleApplicationInfo().sourceDir;
            if (!info.isFirstPackage() || !info.getPackageName().equals(StandaloneRuntime.config(apk).getString("packageName"))) return;
            var driver = new LibXposedDriver(this);
            driver.install(Instrumentation.class.getDeclaredMethod("callApplicationOnCreate", Application.class), HookDriver.Mode.AFTER,
                p -> StandaloneRuntime.start(apk, (Application) p.args[0], driver));
        } catch (Throwable error) { android.util.Log.e("EasylspStandalone", "libxposed initialization failed (API " + getApiVersion() + ")", error); }
    }
}
