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
        if (mode.equals("native-plugin-namespace")) {
            try {
                java.util.ArrayList<java.nio.ByteBuffer> dex = new java.util.ArrayList<>();
                try (java.util.zip.ZipFile apk = new java.util.zip.ZipFile(getApplicationInfo().sourceDir)) {
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = apk.entries();
                    while (entries.hasMoreElements()) {
                        java.util.zip.ZipEntry entry = entries.nextElement();
                        if (entry.getName().matches("classes[0-9]*\\.dex")) dex.add(java.nio.ByteBuffer.wrap(readDex(apk, entry)));
                    }
                }
                for (int i = 0; i < 2; i++) {
                    String directory = new File(getFilesDir(), "namespace/" + i).getAbsolutePath();
                    ClassLoader loader = new dalvik.system.InMemoryDexClassLoader(dex.toArray(new java.nio.ByteBuffer[0]), directory, getClassLoader().getParent());
                    Class<?> probe = loader.loadClass("io.github.easylsp.demo.NamespaceProbe");
                    Object result = probe.getMethod("load", String.class, String.class).invoke(null, directory, i == 0 ? "LIBXPOSED" : "ZYGISK");
                    android.util.Log.i("EasylspDemoNative", "namespace loader=" + i + " result=" + result);
                }
            } catch (ReflectiveOperationException | java.io.IOException error) { throw new IllegalStateException(error); }
            return;
        }
        if (mode.equals("native-plugin-jni-register")) {
            System.loadLibrary("demo_dynamic_jni");
            try {
                android.util.Log.i("EasylspDemoNative", "JNI dynamic primary=" + DynamicTarget.value(7) + " overload=" + DynamicTarget.value(2, 3));
                java.util.ArrayList<java.nio.ByteBuffer> dex = new java.util.ArrayList<>();
                try (java.util.zip.ZipFile apk = new java.util.zip.ZipFile(getApplicationInfo().sourceDir)) {
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = apk.entries();
                    while (entries.hasMoreElements()) {
                        java.util.zip.ZipEntry entry = entries.nextElement();
                        if (entry.getName().matches("classes[0-9]*\\.dex")) dex.add(java.nio.ByteBuffer.wrap(readDex(apk, entry)));
                    }
                }
                for (int i = 0; i < 2; i++) {
                    ClassLoader loader = new dalvik.system.InMemoryDexClassLoader(dex.toArray(new java.nio.ByteBuffer[0]), getClassLoader().getParent());
                    Class<?> cls = loader.loadClass("io.github.easylsp.demo.DynamicTarget");
                    int code = registerDynamic(cls);
                    android.util.Log.i("EasylspDemoNative", "JNI isolated loader=" + i + " code=" + code + " value=" + cls.getMethod("value", int.class).invoke(null, 7));
                }
                android.util.Log.i("EasylspDemoNative", "JNI failed registration=" + failDynamic(DynamicTarget.class) + " retained=" + DynamicTarget.value(7));
                int unregistered = unregisterDynamic(DynamicTarget.class);
                boolean missing = false; try { DynamicTarget.value(7); } catch (UnsatisfiedLinkError expected) { missing = true; }
                int registered = registerDynamic(DynamicTarget.class);
                android.util.Log.i("EasylspDemoNative", "JNI unregister=" + unregistered + " missing=" + missing + " reregister=" + registered + " value=" + DynamicTarget.value(7));
                final int expected = DynamicTarget.value(7);
                java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger();
                Thread[] workers = new Thread[4];
                long registerStart = System.nanoTime();
                for (int i = 0; i < workers.length; i++) {
                    workers[i] = new Thread(() -> {
                        for (int round = 0; round < 100; round++) {
                            if (registerDynamic(DynamicTarget.class) != 0 || DynamicTarget.value(7) != expected) errors.incrementAndGet();
                        }
                    });
                    workers[i].start();
                }
                for (Thread worker : workers) worker.join();
                long registerNs = (System.nanoTime() - registerStart) / 400;
                long callStart = System.nanoTime();
                int last = 0; for (int i = 0; i < 20000; i++) last = DynamicTarget.value(7);
                android.util.Log.i("EasylspDemoNative", "JNI threads=4 errors=" + errors.get() + " result=" + last + " registerNs=" + registerNs + " callNs=" + ((System.nanoTime() - callStart) / 20000));
            } catch (ReflectiveOperationException | java.io.IOException | InterruptedException error) { throw new IllegalStateException(error); }
            return;
        }
        if (mode.equals("native-plugin-lifecycle")) {
            System.loadLibrary("demo_plugin_driver"); nativePluginLifecycle(); return;
        }
        if (mode.equals("native-plugin") || mode.equals("native-plugin-m4")) {
            System.loadLibrary("demo_early_hook");
            android.util.Log.i("EasylspDemoNative", "attach constructor_result=" + earlyConstructorResult());
            if (mode.equals("native-plugin-m4")) { System.loadLibrary("demo_plugin_driver"); nativeM4Checks(earlyConstructorResult()); }
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
    private static byte[] readDex(java.util.zip.ZipFile apk, java.util.zip.ZipEntry entry) throws java.io.IOException {
        try (java.io.InputStream input = apk.getInputStream(entry);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
    private static native void nativeM4Checks(int expected);
    private static native int registerDynamic(Class<?> target);
    private static native int unregisterDynamic(Class<?> target);
    private static native int failDynamic(Class<?> target);
    private static native void trigger(int mode);
    static native int earlyConstructorResult();
    private static native void nativePluginLifecycle();
}
