package io.github.easylsp.app

import io.github.easylsp.app.platform.nativeplugins.NativeStatusQuery
import io.github.easylsp.app.platform.tools.PluginSyntax
import io.github.easylsp.app.platform.tools.BuiltinToolSchemas
import io.github.easylsp.app.platform.tools.ToolArguments
import io.github.easylsp.app.platform.diagnostics.DiagnosticEvidence
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CurrentStatusReviewTest {
    @Test fun statusPagingContractsRejectInvalidScopeAndBounds() {
        val schema = BuiltinToolSchemas.create("get_native_plugin_status", mapOf("pluginId" to "string", "scope" to "string", "cursor" to "integer", "limit" to "integer"), listOf("pluginId"))
        for (invalid in listOf(JSONObject().put("scope", "latest_run"), JSONObject().put("cursor", -1), JSONObject().put("limit", 51))) {
            assertThrows(IllegalArgumentException::class.java) { ToolArguments.validate(schema, invalid.put("pluginId", "plugin")) }
        }
        ToolArguments.validate(schema, JSONObject().put("pluginId", "plugin").put("scope", "history").put("limit", 2))
    }
    private fun event(instance: Int, sequence: Int = 1) = JSONObject().put("uid", 12345).put("pid", 100 + instance)
        .put("startTicks", 1000L + instance).put("instanceId", "instance-" + instance).put("pluginId", "plugin")
        .put("buildId", "build").put("generation", "generation").put("sourceRevision", 2).put("dropped", 0)
        .put("sequence", sequence).put("phase", if (sequence == 1) "plugin_initialized" else "hook_hit").put("hookId", if (sequence == 1) 0 else 1)
        .put("sources", JSONArray().put(JSONObject().put("source", "diagnostic").put("detail", "x".repeat(2000))))

    @Test fun currentExcludesHistoryAndPidReuseWhileHistoryPagesWithoutSources() {
        val events = (0..49).flatMap { id -> (1..10).map { event(id, it) } }
        val reused = JSONObject().put("uid", 12345).put("pid", 100).put("startTicks", 9999).put("processName", "target")
        val empty = NativeStatusQuery.page(events, listOf(reused), JSONObject())
        assertEquals(0, empty.getJSONArray("instances").length())
        assertEquals(50, empty.getInt("recordedInstances"))
        assertTrue(empty.toString().length < 300)
        val live = JSONObject().put("uid", 12345).put("pid", 101).put("startTicks", 1001).put("processName", "target:worker")
        val current = NativeStatusQuery.page(events, listOf(live), JSONObject()).getJSONArray("instances")
        assertEquals(1, current.length())
        assertEquals("ALIVE_AT_QUERY", current.getJSONObject(0).getString("liveness"))
        assertEquals(1, current.getJSONObject(0).getInt("hooksWithHitEvidence"))
        assertEquals(0, NativeStatusQuery.page(events, listOf(live), JSONObject().put("processName", "target")).getJSONArray("instances").length())
        val seen = mutableSetOf<String>(); var cursor = 0
        do {
            val page = NativeStatusQuery.page(events, emptyList(), JSONObject().put("scope", "history").put("cursor", cursor).put("limit", 7))
            assertTrue(page.toString().length < 7600)
            val rows = page.getJSONArray("instances")
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                assertFalse(row.has("sources")); assertFalse(row.has("hooks"))
                assertEquals(10, row.getInt("eventCount"))
                assertTrue(seen.add(row.getString("instanceId")))
                assertEquals("read_plugin_logs", row.getJSONObject("evidenceRead").getString("tool"))
            }
            cursor = if (page.isNull("nextCursor")) -1 else page.getInt("nextCursor")
        } while (cursor >= 0)
        assertEquals(50, seen.size)
    }

    @Test fun syntaxCheckDoesNotExecuteAndReportsParserAndLexerLocations() {
        val key = "easylsp.syntax.executed"
        System.clearProperty(key)
        val valid = PluginSyntax.validate("System.setProperty(\"" + key + "\", \"yes\"); void onLoad() { missingRuntimeClass.call(); }")
        assertTrue(valid.getBoolean("valid")); assertFalse(valid.getBoolean("executed")); assertNull(System.getProperty(key))
        val invalid = PluginSyntax.validate("void onLoad() {\n  int value = ;\n}")
        assertFalse(invalid.getBoolean("valid")); assertEquals(2, invalid.getInt("line"))
        assertTrue(invalid.getInt("column") > 0); assertTrue(invalid.getJSONArray("nearbySource").length() > 0)
        val lexical = PluginSyntax.validate("String value = \"unterminated;")
        assertFalse(lexical.getBoolean("valid")); assertEquals(1, lexical.getInt("line"))
    }

    @Test fun diagnosticObservationIsSeparateFromExitReasonAndPreservesIncompleteCollection() {
        val live = JSONObject().put("aliveAtEnd", true).put("exitEvidence", JSONArray())
        val exited = JSONObject().put("aliveAtEnd", false).put("exitEvidence", JSONArray().put(JSONObject()))
        assertEquals("ALIVE_AT_END", DiagnosticEvidence.observation(JSONArray().put(live), true).getString("state"))
        assertEquals("NO_EXIT_EVIDENCE", DiagnosticEvidence.conclusion(JSONArray(), JSONArray()).getString("classification"))
        assertEquals("EXIT_OBSERVED", DiagnosticEvidence.observation(JSONArray().put(exited), true).getString("state"))
        assertEquals("MIXED", DiagnosticEvidence.observation(JSONArray().put(live).put(exited), true).getString("state"))
        assertEquals("NOT_OBSERVED", DiagnosticEvidence.observation(JSONArray(), true).getString("state"))
        assertEquals("OBSERVATION_INCOMPLETE", DiagnosticEvidence.observation(JSONArray().put(live), false).getString("state"))
    }
}
