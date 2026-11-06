package io.github.easylsp.app

import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.platform.tools.RunBinding
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Read-only smoke check against the installed catalog and existing author account. */
class GitHubAuthorToolsDeviceTest {
    @Test fun installedCatalogExposesCompleteAuthorFlowWithSavedAccount() = runBlocking {
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
        val tools = graph.tools.build(RunBinding(null, 0, null, UUID.randomUUID().toString())).associateBy { it.name }
        for (name in listOf("get_github_account", "configure_github_account", "create_github_repository", "commit_github_source", "publish_plugin_release", "submit_market_release")) {
            val tool = requireNotNull(tools[name]) { "Missing author tool: $name" }
            assertEquals("release.github", tool.groupId)
            assertFalse(tool.schema.getJSONObject("properties").has("token"))
            if (name != "get_github_account") assertTrue(tool.writes)
        }
        val account = tools.getValue("get_github_account").invoke(JSONObject())
        assertEquals(graph.github.profile().has("id"), account.getBoolean("configured"))
        assertEquals(setOf("configured") + graph.github.profile().keys().asSequence().toSet(), account.keys().asSequence().toSet())
        assertFalse(account.has("token"))
        assertFalse(account.has("secret"))
        if (account.getBoolean("configured")) {
            val authenticated = graph.github.client.request("GET", "/user") as JSONObject
            assertEquals(account.getLong("id"), authenticated.getLong("id"))
        }
        val group = graph.tools.groupTools().getValue("release.github")
        assertTrue(group.containsAll(listOf("configure_github_account", "create_github_repository", "commit_github_source")))
    }
}
