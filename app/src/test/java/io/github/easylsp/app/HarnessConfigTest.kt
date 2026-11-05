package io.github.easylsp.app

import io.github.easylsp.app.data.ProviderEntity
import io.github.easylsp.app.platform.harness.HarnessConfig
import io.github.easylsp.app.platform.tools.ToolOutputStore
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class HarnessConfigTest {
    private val provider = ProviderEntity("p", "fixture", "openai", "https://example.com", "chosen", maxSteps = 0,
        options = """{"contextWindow":1000000,"contextBudget":200000,"maxOutputTokens":4096,"requestPath":"/v1/responses"}""")
    @Test fun explicitCapacityAndBudgetAndUnlimitedRequestsReachHarness() {
        val config = HarnessConfig.model(provider, "secret", "prompt")
        assertEquals("openai-responses", config.getString("api"))
        assertEquals("https://example.com/v1", config.getString("baseURL"))
        assertEquals(1000000, config.getInt("contextWindow")); assertEquals(200000, config.getInt("contextBudget"))
        assertEquals(0, config.getInt("maxRequests")); assertFalse(config.has("reasoningEffort"))
        assertThrows(IllegalArgumentException::class.java) { HarnessConfig.model(provider.copy(options = "{}"), "", "") }
    }
    @Test fun originalAttachmentsMapToSdkTextAndImages() {
        val attachments = JSONArray().put(JSONObject().put("type", "text").put("text", "attached source"))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/png;base64,YWJj")))
        val blocks = HarnessConfig.input("user", attachments, true)
        assertEquals("attached source", blocks.getJSONObject(1).getString("text"))
        assertEquals("image/png", blocks.getJSONObject(2).getString("mimeType")); assertEquals("YWJj", blocks.getJSONObject(2).getString("data"))
        assertThrows(IllegalArgumentException::class.java) { HarnessConfig.input("user", attachments, false) }
    }
    @Test fun fullToolResultRemainsAvailableWithScopedCursor() {
        val directory = Files.createTempDirectory("tool-output").toFile()
        try {
            val store = ToolOutputStore(directory)
            val result = JSONObject().put("source", "a".repeat(14000))
            val first = store.pageResult("conversation", result)
            val second = store.page("conversation", first.getString("outputId"), first.getInt("nextOffset"), 12000)
            assertEquals(result.toString(), first.getString("text") + second.getString("text"))
            assertTrue(second.isNull("nextOffset"))
            assertThrows(java.io.FileNotFoundException::class.java) { store.page("other", first.getString("outputId"), 0, 12000) }
        } finally { directory.deleteRecursively() }
    }
}
