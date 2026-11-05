package io.github.easylsp.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Persistent snapshot read once at the first actual package callback, independent of Binder. */
final class NativeStartupConfig {
    static final String PREFERENCES = "native-plugins";
    final String generation, policy;
    final List<JSONObject> plugins;
    private NativeStartupConfig(String generation, String policy, List<JSONObject> plugins) {
        this.generation = generation; this.policy = policy; this.plugins = plugins;
    }
    static NativeStartupConfig parse(String value, int uid, String packageName, String apkPath, String processName) throws Exception {
        if (value == null) return new NativeStartupConfig("", "JAVA_ONLY", List.of());
        JSONObject snapshot = new JSONObject(value);
        require(snapshot.getInt("schema") == 1, "Unsupported native snapshot schema");
        require(snapshot.getInt("uid") == uid && snapshot.getString("packageName").equals(packageName), "Native target identity mismatch; republish");
        require(snapshot.getString("targetApkPath").equals(apkPath), "Installed APK changed; republish");
        String generation = identifier(snapshot.getString("generation"));
        String policy = snapshot.getString("policy");
        require(policy.equals("JAVA_ONLY") || policy.equals("NATIVE_ONLY") || policy.equals("NATIVE_AND_JAVA"), "Unknown native policy");
        JSONArray items = snapshot.getJSONArray("plugins");
        require(items.length() <= 16 && (!policy.equals("JAVA_ONLY") || items.length() == 0), "Invalid native plugin count/policy");
        List<JSONObject> plugins = new ArrayList<>();
        HashSet<String> ids = new HashSet<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            require(ids.add(identifier(item.getString("pluginId"))), "Duplicate plugin ID");
            identifier(item.getString("buildId"));
            require(item.getLong("sourceRevision") > 0, "Invalid source revision");
            require(item.getString("remoteFile").matches("native-[a-zA-Z0-9-]+\\.so"), "Invalid remote artifact name");
            require(item.optString("config", "{}").getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 8192, "Native config too large");
            new JSONObject(item.optString("config", "{}"));
            if (matches(item.getString("processScope"), item.getString("processName"), packageName, processName)) plugins.add(item);
        }
        return new NativeStartupConfig(generation, policy, plugins);
    }
    static boolean matches(String scope, String selected, String packageName, String processName) {
        return switch (scope) {
            case "exact" -> selected.equals(processName);
            case "main" -> packageName.equals(processName);
            case "children" -> processName.startsWith(packageName + ":");
            case "all" -> packageName.equals(processName) || processName.startsWith(packageName + ":");
            default -> throw new IllegalArgumentException("Unknown native process scope");
        };
    }
    private static String identifier(String value) {
        require(value.matches("[a-zA-Z0-9_-]{1,63}"), "Invalid native identifier"); return value;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
