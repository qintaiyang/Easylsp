package io.github.easylsp.runtime;

import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class DebugValueTest {
    @Test public void preservesNestedTypesAndNullInsteadOfEncodingJsonStrings() throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("number", 42); input.put("boolean", true); input.put("missing", null);
        input.put("array", new int[]{1, 2}); input.put("list", List.of(Map.of("name", "value")));
        input.put("json", new JSONObject().put("object", new JSONObject().put("id", 3)));
        JSONObject result = (JSONObject) DebugValue.encode(input);
        assertEquals(42, result.getInt("number")); assertTrue(result.getBoolean("boolean")); assertTrue(result.isNull("missing"));
        assertEquals(2, result.getJSONArray("array").getInt(1));
        assertEquals("value", result.getJSONArray("list").getJSONObject(0).getString("name"));
        assertEquals(3, result.getJSONObject("json").getJSONObject("object").getInt("id"));
        assertEquals("{not JSON}", DebugValue.encode("{not JSON}"));
        assertEquals(JSONObject.NULL, DebugValue.encode(null));
    }
    @Test public void cyclesAndLargeGraphsReturnExplicitBounds() throws Exception {
        Map<String, Object> cycle = new LinkedHashMap<>(); cycle.put("self", cycle);
        assertEquals("CYCLE", ((JSONObject) DebugValue.encode(cycle)).getJSONObject("self").getString("kind"));
        JSONArray result = (JSONArray) DebugValue.encode(Collections.nCopies(10000, 1));
        assertTrue(result.length() <= 256); assertEquals("LIMIT", result.getJSONObject(result.length() - 1).getString("kind"));
        assertTrue(((JSONObject) DebugValue.encode("x".repeat(5000))).getBoolean("truncated"));
    }
    @Test public void reflectionFieldsStayStructured() throws Exception {
        class Example { int count = 7; boolean enabled = true; String label = "demo"; }
        JSONObject result = (JSONObject) DebugValue.encode(new PluginReflection(getClass().getClassLoader()).inspect(new Example()));
        assertEquals(7, result.getInt(Example.class.getName() + ".count"));
        assertTrue(result.getBoolean(Example.class.getName() + ".enabled"));
    }
}
