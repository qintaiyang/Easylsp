package io.github.easylsp.app

import io.github.easylsp.app.platform.tools.ToolCapabilities
import io.github.easylsp.app.platform.tools.ToolDefinition
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolCapabilitiesTest {
    private fun tool(name: String, default: Boolean = false) = ToolDefinition(name, "用途 $name", JSONObject(),
        invoke = { JSONObject() }, groupId = "fixture", defaultAvailable = default)
    private fun select(vararg names: String, mode: String = "add") = JSONObject().put("names", JSONArray(names.toList())).put("mode", mode)
    private fun names(state: JSONObject, key: String = "selectedTools") = state.getJSONArray(key).let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }

    @Test fun selectionsApplyOnlyAtTheNextStepAndSurviveRestoration() {
        var saved = JSONObject()
        val registry = listOf(tool("run_command", true), tool("native_probe"), tool("future_tool"))
        val capabilities = ToolCapabilities(registry, persist = { saved = JSONObject(it.toString()) })
        capabilities.resolve()
        assertTrue(capabilities.permits("run_command"))
        capabilities.select(select("native_probe"))
        assertFalse(capabilities.permits("native_probe"))
        capabilities.resolve()
        assertTrue(capabilities.permits("native_probe"))
        capabilities.select(select("future_tool"))
        assertTrue(names(capabilities.snapshot()).containsAll(listOf("native_probe", "future_tool")))
        capabilities.select(select("future_tool", mode = "replace"))
        assertTrue(capabilities.permits("native_probe"))
        capabilities.resolve()
        assertFalse(capabilities.permits("native_probe"))
        assertTrue(capabilities.permits("future_tool"))
        val restored = ToolCapabilities(registry, saved = saved)
        assertEquals(capabilities.snapshot().toString(), restored.snapshot().toString())
        restored.select(select("run_command", "future_tool", mode = "remove"))
        restored.resolve()
        assertTrue(restored.permits("run_command"))
        assertFalse(restored.permits("future_tool"))
    }

    @Test fun registrySearchPagesAllToolsAndReportsRealAvailabilityWithoutSkills() {
        val registry = (0..11).map { tool("new_tool_$it") } + tool("disabled") + tool("outside_mode")
        val capabilities = ToolCapabilities(registry, mapOf("disabled" to "用户已停用", "outside_mode" to "当前模式不可用"))
        var cursor = 0
        val found = mutableSetOf<String>()
        do {
            val page = capabilities.search(JSONObject().put("query", "new_tool").put("cursor", cursor))
            page.getJSONArray("tools").let { for (i in 0 until it.length()) found += it.getJSONObject(i).getString("name") }
            cursor = if (page.isNull("nextCursor")) -1 else page.getInt("nextCursor")
        } while (cursor >= 0)
        assertEquals(12, found.size)
        val selection = capabilities.select(select("new_tool_11", "disabled", "outside_mode", "missing"))
        assertTrue("new_tool_11" in names(selection))
        assertEquals(3, selection.getJSONArray("unavailableTools").length())
        val disabled = capabilities.search(JSONObject().put("query", "disabled")).getJSONArray("tools").getJSONObject(0)
        assertEquals("用户已停用", disabled.getJSONObject("availability").getString("reason"))
        assertTrue(capabilities.search(JSONObject().put("query", "fixture").put("limit", 100)).getJSONArray("tools").length() > 5)
    }

    @Test fun restoredSelectionDoesNotOverrideNewDisablementOrRemovedTools() {
        var saved = JSONObject()
        val before = ToolCapabilities(listOf(tool("a"), tool("b")), persist = { saved = it })
        before.select(select("a", "b")); before.resolve()
        val after = ToolCapabilities(listOf(tool("a")), mapOf("a" to "用户已停用"), saved)
        assertTrue(after.snapshot().getLong("toolSetVersion") > before.snapshot().getLong("toolSetVersion"))
        after.resolve()
        assertFalse(after.permits("a")); assertFalse(after.permits("b"))
        assertEquals(setOf("search_tools", "select_tools"), names(after.snapshot()))
        assertEquals(4, after.snapshot().getJSONArray("selections").length())
    }
}
