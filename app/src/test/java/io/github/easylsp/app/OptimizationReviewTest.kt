package io.github.easylsp.app

import io.github.easylsp.app.data.ToolRunEntity
import io.github.easylsp.app.platform.ai.ModelCapabilities
import io.github.easylsp.app.platform.harness.HarnessProjection
import io.github.easylsp.app.platform.tools.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class OptimizationReviewTest {
    @Test fun escapedUnicodePagesRetainEveryCodeUnitBelowPruningThreshold() {
        val directory = Files.createTempDirectory("paged-output").toFile()
        try {
            val store = ToolOutputStore(directory)
            val source = JSONObject().put("value", "😀中文\\\"\n\t".repeat(5000))
            var page = store.pageResult("conversation", source)
            val output = StringBuilder()
            val pages = JSONArray()
            while (true) {
                assertTrue(page.toString().length < 8192)
                assertEquals(output.length, page.getInt("offset"))
                output.append(page.getString("text"))
                pages.put(page)
                if (page.isNull("nextOffset")) break
                page = store.page("conversation", page.getString("outputId"), page.getInt("nextOffset"), 12000)
            }
            assertEquals(source.toString(), output.toString())
            assertEquals(source.getString("value"), JSONObject(output.toString()).getString("value"))
            java.io.File("build/tmp/optimization-output-pages.json").apply {
                parentFile!!.mkdirs(); writeText(JSONObject().put("original", source.toString()).put("pages", pages).toString())
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun legacyOutputCanBeSelectedBeforeReadingAnyPage() {
        val directory = Files.createTempDirectory("legacy-output").toFile()
        try {
            val id = java.util.UUID.randomUUID().toString()
            java.io.File(directory, "conversation/$id.json").apply { parentFile!!.mkdirs(); writeText("{\"rows\":[{\"kind\":\"keep\",\"value\":42},{\"kind\":\"skip\",\"value\":0}]}") }
            val selected = ToolOutputStore(directory).select("conversation", id, "rows", JSONObject().put("kind", "keep"))
            assertEquals(1, selected.getJSONArray("value").length())
            assertEquals(42, selected.getJSONArray("value").getJSONObject(0).getInt("value"))
        } finally { directory.deleteRecursively() }
    }
    @Test fun compactionCannotChangeOriginalToolEvidence() {
        val running = ToolRunEntity("id", "conversation", "call", "tool", "{}", status = "RUNNING", startedAt = 1000)
        val event = JSONObject("""{"type":"tool/result","data":{"message":{"toolCallId":"call","content":[{"type":"text","text":"original"}]}}}""")
        val original = HarnessProjection.originalToolResult(running, event, 1108)
        val pruned = JSONObject(event.toString()).put("surfaceOp", JSONObject().put("op", "replace").put("startSeq", 3).put("endSeq", 3))
        pruned.getJSONObject("data").getJSONObject("message").getJSONArray("content").getJSONObject(0).put("text", "clipped")
        assertSame(original, HarnessProjection.originalToolResult(original, pruned, 1176177))
        assertSame(running, HarnessProjection.originalToolResult(running, pruned, 1176177))
        assertEquals(1108L, original.finishedAt); assertEquals("original", original.result); assertEquals("COMPLETE", original.status)
    }
    @Test fun capabilitySourcesRespectGatewayAndUserLimits() {
        val official = ModelCapabilities.resolve("gpt-6.1-sol", "https://api.openai.com/v1", JSONObject())
        assertEquals(1050000, official.getInt("contextWindow")); assertEquals(200000, official.getInt("contextBudget"))
        val gateway = ModelCapabilities.resolve("gpt-6.1-sol", "https://gateway.example/v1", JSONObject(), JSONObject().put("contextWindow", 64000))
        assertEquals(64000, gateway.getInt("contextWindow")); assertFalse(gateway.has("reasoningEfforts"))
        val user = ModelCapabilities.resolve("gpt-6.1-sol", "https://api.openai.com/v1", JSONObject().put("contextWindow", 32000), JSONObject().put("contextWindow", 64000))
        assertEquals(32000, user.getInt("contextWindow"))
        assertEquals("user", user.getJSONObject("capabilitySources").getString("contextWindow"))
        val filled = ModelCapabilities.resolve("gpt-6.1-sol", "https://gateway.example/v1", user,
            ModelCapabilities.resolve("gpt-6.1-sol", "https://gateway.example/v1", JSONObject(), JSONObject().put("modelMaxOutputTokens", 4096)))
        assertEquals(32000, filled.getInt("contextWindow"))
        assertEquals(128000, filled.getInt("modelMaxOutputTokens")) // Previously known fields are not overwritten on refresh.
        assertEquals(4096, ModelCapabilities.resolve("unknown", "https://gateway.example/v1", JSONObject().put("contextWindow", 32000),
            JSONObject().put("modelMaxOutputTokens", 4096)).getInt("modelMaxOutputTokens"))
        assertFalse(ModelCapabilities.resolve("unknown", "https://api.openai.com/v1", JSONObject()).has("contextWindow"))
        assertThrows(IllegalArgumentException::class.java) { ModelCapabilities.validate(JSONObject(official.toString()).put("reasoningEffort", "turbo")) }
    }
    @Test fun inspectorAndAnalysisRejectUnsupportedArguments() {
        ToolArguments.validate(BuiltinToolSchemas.analysis("methods"), JSONObject().put("owner", "pkg.Class"))
        assertThrows(IllegalArgumentException::class.java) { ToolArguments.validate(BuiltinToolSchemas.analysis("methods"), JSONObject().put("className", "pkg.Class")) }
        assertThrows(IllegalArgumentException::class.java) { ToolArguments.validate(BuiltinToolSchemas.inspection("viewTree"), JSONObject().put("maxDepth", 10)) }
        assertThrows(IllegalArgumentException::class.java) { ToolArguments.validate(BuiltinToolSchemas.inspection("viewTree"), JSONObject().put("limit", 101)) }
    }
}
