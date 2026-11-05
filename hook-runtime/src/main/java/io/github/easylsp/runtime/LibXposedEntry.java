package io.github.easylsp.runtime;
import android.app.*;
import io.github.libxposed.api.*;
import io.github.easylsp.api.HookDriver;
public final class LibXposedEntry extends XposedModule {
    private StartupDiagnostic diagnostic = StartupDiagnostic.NONE;
    private boolean diagnosticConfigFailed;
    private String processName;
    private boolean skipJava;
    @Override public void onModuleLoaded(ModuleLoadedParam info) {
        if (info.isSystemServer()) return;
        processName = info.getProcessName();
        try {
            diagnostic = StartupDiagnostic.read(getRemotePreferences(StartupDiagnostic.PREFERENCES), info.getProcessName());
            diagnostic.event("module_loaded");
        } catch (Exception error) {
            diagnosticConfigFailed = true;
            android.util.Log.e("EasylspDiag", "diagnostic_config_failed; Java initialization skipped", error);
        }
    }
    @Override public void onPackageLoaded(PackageLoadedParam info) {
        if (!info.isFirstPackage() || diagnosticConfigFailed) return;
        diagnostic.event("package_loaded");
        if (diagnostic.mode.equals("NATIVE_OBSERVE")) {
            try {
                int result = io.github.easylsp.nativeengine.NativeRuntime.start(diagnostic.nativeSocket);
                diagnostic.event("native_start_result", "code=" + result);
            } catch (Throwable error) {
                diagnostic.event("native_start_failed", error.toString());
            }
            return;
        }
        if (diagnostic.entryOnly() || diagnostic.suppressAutoLoad()) return;
        try {
            String value = getRemotePreferences(NativeStartupConfig.PREFERENCES)
                .getString(android.os.Process.myUid() + ":" + info.getPackageName(), null);
            NativeStartupConfig config = NativeStartupConfig.parse(value, android.os.Process.myUid(),
                info.getPackageName(), info.getApplicationInfo().sourceDir, processName);
            skipJava = config.policy.equals("NATIVE_ONLY");
            for (org.json.JSONObject plugin : config.plugins) {
                String instance = java.util.UUID.randomUUID().toString();
                try (android.os.ParcelFileDescriptor fd = openRemoteFile(plugin.getString("remoteFile"))) {
                    int code = io.github.easylsp.nativeengine.NativeRuntime.loadPlugin(fd.getFd(),
                        plugin.getString("pluginId"), plugin.getString("buildId"), instance, info.getPackageName(),
                        processName, config.generation, plugin.getLong("sourceRevision"), plugin.optString("config", "{}"));
                    android.util.Log.i("EasylspNative", "native_plugin_result instanceId=" + instance + " buildId=" + plugin.getString("buildId") + " code=" + code);
                    diagnostic.event("native_plugin_result", "instanceId=" + instance + ";code=" + code);
                } catch (Throwable error) {
                    android.util.Log.e("EasylspNative", "native_plugin_load_failed instanceId=" + instance + " buildId=" + plugin.getString("buildId"), error);
                }
            }
        } catch (Exception error) {
            skipJava = true;
            android.util.Log.e("EasylspNative", "native_config_failed; package initialization skipped", error);
        }
    }
    @Override public void onPackageReady(PackageReadyParam info) {
        if (diagnosticConfigFailed || diagnostic.entryOnly() || skipJava) return;
        try {
            LibXposedDriver driver = new LibXposedDriver(this);
            if (info.isFirstPackage()) {
                driver.install(Instrumentation.class.getDeclaredMethod("callApplicationOnCreate", Application.class), HookDriver.Mode.AFTER,
                    p -> { diagnostic.event("application_onCreate_returned"); RuntimeBootstrap.start((Application) p.args[0], driver, diagnostic); });
                diagnostic.event("java_hook_installed");
            }
        } catch (Throwable error) { android.util.Log.e("Easylsp", "libxposed 初始化失败（API " + getApiVersion() + "）", error); }
    }
}
