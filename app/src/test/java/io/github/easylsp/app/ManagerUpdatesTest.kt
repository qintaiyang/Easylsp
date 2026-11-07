package io.github.easylsp.app

import io.github.easylsp.app.platform.AppLinks
import io.github.easylsp.app.platform.ManagerUpdates
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ManagerUpdatesTest {
    private fun release(tag: String, draft: Boolean = false, prerelease: Boolean = false) = MockResponse().setBody(
        JSONObject().put("tag_name", tag).put("draft", draft).put("prerelease", prerelease).toString()
    )

    @Test fun detectsNewerNumericVersionAndUsesPublicReleasePage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(release("v0.10.0"))
            server.start()
            val update = ManagerUpdates(server.url("/releases/latest").toString()).check("0.9.3")!!
            assertEquals("0.10.0", update.version)
            assertEquals(AppLinks.SOURCE + "/releases/tag/v0.10.0", update.url)
            val request = server.takeRequest()
            assertEquals("/releases/latest", request.path)
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test fun hidesButtonForEqualOlderAndNonStableReleases() = runBlocking {
        MockWebServer().use { server ->
            listOf(release("v0.9.3"), release("v0.9.2"), release("v0.9.4", draft = true),
                release("v1.0.0", prerelease = true), release("v1.0.0-rc1"), release("unexpected")).forEach(server::enqueue)
            server.start()
            val updates = ManagerUpdates(server.url("/").toString())
            repeat(6) { assertNull(updates.check("0.9.3")) }
        }
    }

    @Test fun detectsPatchAndMajorUpgrades() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(release("0.9.4"))
            server.enqueue(release("v1.0.0"))
            server.start()
            val updates = ManagerUpdates(server.url("/").toString())
            assertEquals("0.9.4", updates.check("0.9.3")!!.version)
            assertEquals("1.0.0", updates.check("0.9.3")!!.version)
        }
    }

    @Test fun reportsNetworkAndMalformedResponseFailures() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setBody("invalid json"))
            server.start()
            val updates = ManagerUpdates(server.url("/").toString())
            repeat(2) { assertTrue(runCatching { updates.check("0.9.3") }.isFailure) }
        }
    }
}
