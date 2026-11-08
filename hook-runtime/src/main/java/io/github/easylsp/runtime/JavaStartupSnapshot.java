package io.github.easylsp.runtime;

import android.app.Application;
import android.os.ParcelFileDescriptor;
import org.json.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Snapshot contains fixed revisions and ordered DEX/APK libraries. No manager Binder required. */
final class JavaStartupSnapshot {
    interface RemoteFile { ParcelFileDescriptor open(String name) throws Exception; }
    private final JSONObject snapshot;
    private final RemoteFile files;
    boolean debugRequested;
    JavaStartupSnapshot(String value, RemoteFile files) throws JSONException {
        snapshot = value == null ? null : new JSONObject(value); this.files = files;
    }
    boolean published() { return snapshot != null; }
    void load(Application app, PluginEngine engine) throws Exception {
        if (snapshot == null) return;
        if ((snapshot.getInt("schema") != 1 && snapshot.getInt("schema") != 2) || snapshot.getInt("uid") != android.os.Process.myUid()
                || !snapshot.getString("packageName").equals(app.getPackageName())
                || !snapshot.getString("targetApkPath").equals(app.getApplicationInfo().sourceDir))
            throw new IllegalArgumentException("Java 发布目标已变化，请重新发布");
        JSONArray plugins = snapshot.getJSONArray("plugins");
        for (int i = 0; i < plugins.length(); i++) {
            JSONObject item = plugins.getJSONObject(i);
            if (!NativeStartupConfig.matches(item.getString("processScope"), item.getString("processName"), app.getPackageName(), Application.getProcessName())) continue;
            String id = item.getString("pluginId"); long revision = item.getLong("revision");
            try {
                if (engine.alreadyLoaded(id, revision)) continue;
                ArrayList<ByteBuffer> buffers = new ArrayList<>();
                JSONArray libraries = item.getJSONArray("libraries");
                for (int j = 0; j < libraries.length(); j++) {
                    JSONObject library = libraries.getJSONObject(j);
                    try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(files.open(library.getString("remoteFile")))) {
                        if (library.getString("name").endsWith(".dex")) buffers.add(ByteBuffer.wrap(StreamIO.readAll(input)));
                        else try (ZipInputStream zip = new ZipInputStream(input)) {
                            ZipEntry entry;
                            while ((entry = zip.getNextEntry()) != null) if (entry.getName().matches("classes(?:[0-9]+)?\\.dex")) buffers.add(ByteBuffer.wrap(StreamIO.readAll(zip)));
                        }
                    }
                }
                ClassLoader loader = buffers.isEmpty() ? app.getClassLoader() : new dalvik.system.InMemoryDexClassLoader(buffers.toArray(new ByteBuffer[0]), app.getClassLoader());
                try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(files.open(item.getString("remoteFile")))) {
                    String format = item.optString("programFormat", "script-v1");
                    if (PluginProgram.FORMAT.equals(format)) engine.load(id, revision, PluginProgram.read(input), loader);
                    else if ("script-v1".equals(format)) engine.load(id, revision, new InputStreamReader(input, StandardCharsets.UTF_8), loader);
                    else throw new IllegalArgumentException("Unsupported Java program format: " + format);
                }
                android.util.Log.i("Easylsp", "Java published instance: " + id + " r" + revision + " generation=" + snapshot.getString("generation"));
            } catch (Throwable error) { android.util.Log.e("Easylsp", "Java snapshot load failed: " + id + " r" + revision + " generation=" + snapshot.getString("generation"), error); }
        }
    }
}
