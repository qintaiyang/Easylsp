package io.github.easylsp.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeStartupConfigTest {
    private JSONObject snapshot() throws Exception {
        return new JSONObject().put("schema", 1).put("uid", 10234).put("packageName", "demo.app")
            .put("targetApkPath", "/data/app/install/base.apk").put("generation", "generation-1").put("policy", "NATIVE_ONLY")
            .put("plugins", new JSONArray().put(new JSONObject().put("pluginId", "plugin-1").put("buildId", "build-1")
                .put("sourceRevision", 7).put("remoteFile", "native-artifact-1.so").put("processName", "demo.app").put("processScope", "main")));
    }
    private NativeStartupConfig parse(JSONObject value, String process) throws Exception {
        return NativeStartupConfig.parse(value.toString(), 10234, "demo.app", "/data/app/install/base.apk", process);
    }
    @Test public void absentSnapshotPreservesJava() throws Exception {
        assertEquals("JAVA_ONLY", NativeStartupConfig.parse(null, 10234, "demo.app", "/base.apk", "demo.app").policy);
    }
    @Test public void fixedBuildSurvivesWithoutSessionAndProcessScopeIsApplied() throws Exception {
        NativeStartupConfig main = parse(snapshot(), "demo.app");
        assertEquals("NATIVE_ONLY", main.policy);
        assertEquals("build-1", main.plugins.get(0).getString("buildId"));
        assertEquals(7, main.plugins.get(0).getLong("sourceRevision"));
        assertTrue(parse(snapshot(), "demo.app:worker").plugins.isEmpty());
        assertTrue(NativeStartupConfig.matches("exact", "custom.worker", "demo.app", "custom.worker"));
        assertTrue(NativeStartupConfig.matches("children", "", "demo.app", "demo.app:worker"));
        assertFalse(NativeStartupConfig.matches("children", "", "demo.app", "demo.app"));
        assertFalse(NativeStartupConfig.matches("all", "", "demo.app", "unrelated.app"));
    }
    @Test public void reinstallsAndUpgradesRequireRepublish() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> parse(snapshot().put("uid", 10235), "demo.app"));
        assertThrows(IllegalArgumentException.class, () -> parse(snapshot().put("packageName", "different.app"), "demo.app"));
        assertThrows(IllegalArgumentException.class, () -> parse(snapshot().put("targetApkPath", "/data/app/old/base.apk"), "demo.app"));
    }
    @Test public void rejectsUnsafeFilesUnknownSchemaAndDuplicatePlugin() throws Exception {
        JSONObject unsafe = snapshot();
        unsafe.getJSONArray("plugins").getJSONObject(0).put("remoteFile", "../plugin.so");
        assertThrows(IllegalArgumentException.class, () -> parse(unsafe, "demo.app"));
        assertThrows(IllegalArgumentException.class, () -> parse(snapshot().put("schema", 2), "demo.app"));
        assertThrows(IllegalArgumentException.class, () -> parse(snapshot().put("policy", "UNKNOWN"), "demo.app"));
        JSONObject duplicate = snapshot();
        duplicate.getJSONArray("plugins").put(duplicate.getJSONArray("plugins").getJSONObject(0));
        assertThrows(IllegalArgumentException.class, () -> parse(duplicate, "demo.app"));
    }
    @Test public void disabledSnapshotRestoresJavaOnNextProcess() throws Exception {
        NativeStartupConfig disabled = parse(snapshot().put("policy", "JAVA_ONLY").put("plugins", new JSONArray()), "demo.app");
        assertTrue(disabled.plugins.isEmpty());
        assertEquals("JAVA_ONLY", disabled.policy);
        assertThrows(IllegalArgumentException.class, () -> parse(snapshot().put("policy", "JAVA_ONLY"), "demo.app"));
    }
}
