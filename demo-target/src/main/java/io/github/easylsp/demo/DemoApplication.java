package io.github.easylsp.demo;

import android.app.Application;
import android.content.Context;
import java.io.File;
import java.nio.file.Files;

/** Opt-in, one-shot fixture read before Application.onCreate and the Easylsp session. */
public final class DemoApplication extends Application {
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        if (!Application.getProcessName().equals(getPackageName())) return;
        File fixture = new File(getFilesDir(), "startup-fault.txt");
        android.util.Log.i("EasylspDemoFault", "attach " + fixture + " exists=" + fixture.exists());
        if (!fixture.exists()) return;
        final String mode;
        try { mode = new String(Files.readAllBytes(fixture.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim(); Files.delete(fixture.toPath()); }
        catch (java.io.IOException error) { throw new IllegalStateException(error); }
        android.util.Log.i("EasylspDemoFault", "trigger " + mode);
        if (mode.equals("java")) throw new IllegalStateException("Easylsp controlled early Java failure");
        if (mode.equals("native-plugin-lifecycle")) {
            System.loadLibrary("demo_plugin_driver"); nativePluginLifecycle(); return;
        }
        if (mode.equals("native-plugin")) {
            System.loadLibrary("demo_early_hook");
            android.util.Log.i("EasylspDemoNative", "attach constructor_result=" + earlyConstructorResult());
            return;
        }
        if (mode.equals("constructor")) System.loadLibrary("demo_constructor_fault");
        else if (mode.equals("jni")) System.loadLibrary("demo_jni_fault");
        else {
            System.loadLibrary("demo_fault");
            switch (mode) {
                case "segv" -> trigger(1);
                case "abort" -> trigger(2);
                case "exit" -> trigger(3);
                case "kill" -> trigger(4);
                default -> throw new IllegalArgumentException("Unknown startup fixture: " + mode);
            }
        }
    }
    private static native void trigger(int mode);
    private static native int earlyConstructorResult();
    private static native void nativePluginLifecycle();
}
