package io.github.easylsp.runtime;

import org.json.*;
import java.util.*;
import java.lang.reflect.Array;

/** Script results are untrusted object graphs; bound traversal and preserve JSON value types. */
final class DebugValue {
    static Object encode(Object value) throws JSONException { return encode(value, 0, new IdentityHashMap<>(), new int[]{256}); }
    private static Object encode(Object value, int depth, IdentityHashMap<Object, Boolean> ancestors, int[] remaining) throws JSONException {
        if (value == null || value == JSONObject.NULL || value == bsh.Primitive.NULL || value == bsh.Primitive.VOID) return JSONObject.NULL;
        if (value instanceof bsh.Primitive primitive) value = primitive.getValue();
        if (remaining[0]-- <= 0 || depth > 8) return marker("LIMIT", value);
        if (value instanceof String || value instanceof Character) {
            String text = value.toString();
            return text.length() <= 4000 ? text : new JSONObject().put("truncated", true).put("text", text.substring(0, 4000)).put("length", text.length());
        }
        if (value instanceof Boolean) return value;
        if (value instanceof Number number) return Double.isFinite(number.doubleValue()) ? number : marker("NON_FINITE_NUMBER", value);
        if (ancestors.put(value, true) != null) return marker("CYCLE", value);
        try {
            if (value instanceof JSONObject object) {
                JSONObject out = new JSONObject(); Iterator<String> keys = object.keys();
                while (keys.hasNext() && remaining[0] > 0) { String key = keys.next(); out.put(key, encode(object.get(key), depth + 1, ancestors, remaining)); }
                if (keys.hasNext()) out.put("$truncated", true);
                return out;
            }
            if (value instanceof Map<?, ?> map) {
                JSONObject out = new JSONObject(); Iterator<? extends Map.Entry<?, ?>> entries = map.entrySet().iterator();
                while (entries.hasNext() && remaining[0] > 0) { var entry = entries.next(); out.put(String.valueOf(entry.getKey()), encode(entry.getValue(), depth + 1, ancestors, remaining)); }
                if (entries.hasNext()) out.put("$truncated", true);
                return out;
            }
            if (value instanceof JSONArray || value instanceof Collection<?> || value.getClass().isArray()) {
                JSONArray out = new JSONArray();
                if (value instanceof Collection<?> collection) {
                    Iterator<?> entries = collection.iterator();
                    while (entries.hasNext() && remaining[0] > 0) out.put(encode(entries.next(), depth + 1, ancestors, remaining));
                    if (entries.hasNext()) out.put(marker("LIMIT", value));
                } else {
                    int size = value instanceof JSONArray array ? array.length() : Array.getLength(value); int i = 0;
                    for (; i < size && remaining[0] > 0; i++) out.put(encode(value instanceof JSONArray array ? array.get(i) : Array.get(value, i), depth + 1, ancestors, remaining));
                    if (i < size) out.put(marker("LIMIT", value));
                }
                return out;
            }
            return marker("OBJECT_REFERENCE", value);
        } finally { ancestors.remove(value); }
    }
    private static JSONObject marker(String reason, Object value) throws JSONException {
        return new JSONObject().put("kind", reason).put("class", value.getClass().getName());
    }
}
