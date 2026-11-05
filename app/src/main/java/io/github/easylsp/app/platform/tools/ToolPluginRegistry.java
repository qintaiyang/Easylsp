package io.github.easylsp.app.platform.tools;
import java.util.*;
import org.json.*;
/** Separate from target plugin globals. No target Context or hook objects are supplied. */
public final class ToolPluginRegistry {
    public record Entry(String name, String description, JSONObject schema, ToolFunction callback) { }
    public final Map<String, Entry> entries = new LinkedHashMap<>();
    public void addTool(String name, String description, String schemaJson, ToolFunction callback) throws JSONException {
        if (!name.matches("[a-zA-Z0-9_]{1,48}")) throw new IllegalArgumentException("工具名只允许字母、数字、下划线，最长 48 字符");
        JSONObject schema = new JSONObject(schemaJson);
        if (!"object".equals(schema.getString("type"))) throw new IllegalArgumentException("参数 schema 必须为 object");
        if (entries.containsKey(name)) throw new IllegalArgumentException("重复工具名：" + name);
        entries.put(name, new Entry(name, description, schema, callback));
    }
}
