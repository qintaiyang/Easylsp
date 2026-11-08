package io.github.easylsp.standalone;

import android.app.Application;
import android.util.Log;
import io.github.easylsp.api.HookDriver;
import io.github.easylsp.runtime.PluginEngine;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;

final class StandaloneRuntime {
    private static PluginEngine engine;
    static String read(String apk, String entry) throws IOException {
        try (ZipFile zip = new ZipFile(apk); InputStream input = zip.getInputStream(zip.getEntry(entry))) { return new String(io.github.easylsp.runtime.StreamIO.readAll(input), StandardCharsets.UTF_8); }
    }
    static JSONObject config(String apk) throws Exception { return new JSONObject(read(apk, "assets/standalone/config.json")); }
    private static ClassLoader dependencies(String apk, ClassLoader parent) throws IOException {
        java.util.ArrayList<java.nio.ByteBuffer> buffers = new java.util.ArrayList<>();
        try (ZipFile zip = new ZipFile(apk)) {
            for (var entry : java.util.Collections.list(zip.entries())) if (entry.getName().startsWith("assets/standalone/lib/")) {
                try (InputStream input = zip.getInputStream(entry)) {
                    if (entry.getName().endsWith(".dex")) buffers.add(java.nio.ByteBuffer.wrap(io.github.easylsp.runtime.StreamIO.readAll(input)));
                    else try (java.util.zip.ZipInputStream library = new java.util.zip.ZipInputStream(input)) {
                        java.util.zip.ZipEntry dex;
                        while ((dex = library.getNextEntry()) != null) if (dex.getName().matches("classes(?:[0-9]+)?\\.dex")) buffers.add(java.nio.ByteBuffer.wrap(io.github.easylsp.runtime.StreamIO.readAll(library)));
                    }
                }
            }
        }
        return buffers.isEmpty() ? parent : new dalvik.system.InMemoryDexClassLoader(buffers.toArray(new java.nio.ByteBuffer[0]), parent);
    }
    static synchronized void start(String apk, Application app, HookDriver driver) {
        if (engine != null) return;
        try {
            JSONObject config = config(apk);
            if (!app.getPackageName().equals(config.getString("packageName"))) return;
            String scope = config.optString("processScope", "exact"), process = Application.getProcessName(), pkg = app.getPackageName();
            boolean matches = switch (scope) { case "all" -> process.equals(pkg) || process.startsWith(pkg + ":"); case "children" -> process.startsWith(pkg + ":"); case "main" -> process.equals(pkg); default -> process.equals(config.getString("processName")); };
            if (!matches) return;
            engine = new PluginEngine(driver, app.getClassLoader(), app, new PluginEngine.Events() {
                public void state(String id, long rev, String instance, String state, String error) { Log.i("EasylspStandalone", id + " r" + rev + " " + state + " " + error); }
                public void log(String id, long rev, String instance, String level, String message, Throwable error) { Log.println("ERROR".equals(level) ? Log.ERROR : Log.INFO, "EasylspStandalone", id + ": " + message + (error == null ? "" : "\n" + Log.getStackTraceString(error))); }
            });
            if (io.github.easylsp.runtime.PluginProgram.FORMAT.equals(config.optString("programFormat"))) {
                try (ZipFile zip = new ZipFile(apk); InputStream input = zip.getInputStream(zip.getEntry("assets/standalone/program.zip"))) {
                    engine.load(config.getString("id"), config.getLong("revision"), io.github.easylsp.runtime.PluginProgram.read(input), dependencies(apk, app.getClassLoader()));
                }
            } else engine.load(config.getString("id"), config.getLong("revision"), new StringReader(read(apk, "assets/standalone/main.java")), dependencies(apk, app.getClassLoader()));
        } catch (Throwable error) { if (engine != null) engine.close(); engine = null; Log.e("EasylspStandalone", "Plugin load failed", error); }
    }
}
