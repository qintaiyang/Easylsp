package io.github.easylsp.runtime;

import android.app.Application;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.easylsp.api.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs in an isolated test APK; never modifies manager projects or target applications. */
public class PluginProgramDeviceTest {
    @Test public void startupBundleComponentsServicesAndUiCleanup() throws Throwable {
        Application app = (Application) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        List<String> logs = Collections.synchronizedList(new ArrayList<>());
        PluginEngine engine = new PluginEngine((member, mode, callback) -> () -> {}, app.getClassLoader(), app, new PluginEngine.Events() {
            public void state(String id, long rev, String instance, String state, String error) {}
            public void log(String id, long rev, String instance, String level, String message, Throwable error) { logs.add(message); }
        });
        Map<String, String> source = new LinkedHashMap<>();
        JSONObject component = new JSONObject().put("id", "echo").put("path", "src/echo.bsh");
        JSONObject manifest = new JSONObject().put("schema", 1).put("components", new JSONArray().put(component))
            .put("services", new JSONObject().put("provides", new JSONObject().put("device.echo", 1)));
        source.put("plugin.json", manifest.toString());
        source.put("main.java", "void onLoad(){ plugin.component(\"echo\").publish(); }");
        source.put("assets/message.txt", "device-payload");
        source.put("src/echo.bsh", "publish(){ PluginService handler=new PluginService(){ public String call(String op,String request){ return request; } }; plugin.services.provide(\"device.echo\",handler); } void onLoad(){ component.resources.onUiClose(new Runnable(){ public void run(){ plugin.log(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()?\"ui-clean\":\"wrong-thread\"); } }); plugin.log(plugin.readText(\"assets/message.txt\")); } return this;");
        File file = File.createTempFile("program", ".zip", app.getCacheDir());
        try {
            new PluginProgram(source).write(new FileOutputStream(file));
            JSONObject item = new JSONObject().put("pluginId", "device-provider").put("revision", 1).put("remoteFile", "program.zip")
                .put("programFormat", PluginProgram.FORMAT).put("libraries", new JSONArray()).put("processScope", "all").put("processName", app.getPackageName());
            JSONObject snapshot = new JSONObject().put("schema", 2).put("uid", android.os.Process.myUid()).put("packageName", app.getPackageName())
                .put("targetApkPath", app.getApplicationInfo().sourceDir).put("generation", "device-test").put("plugins", new JSONArray().put(item));
            new JavaStartupSnapshot(snapshot.toString(), name -> ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).load(app, engine);
            assertTrue(engine.alreadyLoaded("device-provider", 1));
            JSONObject consumer = new JSONObject().put("schema", 1).put("services", new JSONObject().put("requires", new JSONObject().put("device.echo", 1)));
            Map<String, String> files = new LinkedHashMap<>(); files.put("plugin.json", consumer.toString());
            files.put("main.java", "void onLoad(){ service=plugin.services.require(\"device.echo\"); plugin.log(service.call(\"echo\",\"{}\")); }");
            engine.load("device-consumer", 1, new PluginProgram(files), app.getClassLoader());
            assertTrue(logs.contains("device-payload")); assertTrue(logs.contains("{}"));
            assertThrows(IllegalStateException.class, () -> engine.unload("device-provider"));
            engine.unload("device-consumer"); engine.unload("device-provider");
            assertTrue(logs.toString(), logs.contains("ui-clean")); assertFalse(logs.contains("wrong-thread"));
        } finally { engine.close(); file.delete(); }
    }
}
