package io.github.easylsp.app

import io.github.easylsp.app.platform.market.GitHubAuthoringService
import io.github.easylsp.app.platform.market.GitHubClient
import io.github.easylsp.app.platform.market.GitHubFailure
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.Base64

class GitHubAuthoringTest {
    private val profile = { JSONObject().put("id", 7).put("login", "author") }
    private val repo = """{"owner":{"id":7},"permissions":{"push":true},"default_branch":"main"}"""
    private fun MockWebServer.json(body: String, code: Int = 200) { enqueue(MockResponse().setResponseCode(code).setBody(body)) }
    private fun author(server: MockWebServer) = GitHubAuthoringService(GitHubClient({ "fixture-token" }, server.url("/").toString().trimEnd('/')), profile)

    @Test fun createsRepositoryWithStoredCredentialAndOnlyReturnsPublicMetadata() {
        MockWebServer().use { server ->
            server.json("""{"full_name":"author/plugin","html_url":"https://github.com/author/plugin","private":false,"default_branch":"main"}""", 201)
            val result = author(server).createRepository("plugin", "Plugin source", false)
            val request = server.takeRequest()
            assertEquals("/user/repos", request.path)
            assertEquals("Bearer fixture-token", request.getHeader("Authorization"))
            assertEquals(false, JSONObject(request.body.readUtf8()).getBoolean("private"))
            assertEquals("author/plugin", result.getString("repository"))
            assertFalse(result.toString().contains("fixture-token"))
        }
    }

    @Test fun commitsExplicitBinaryAndSourceFilesTogetherWithoutUploadingConversationOrNotes() {
        val root = Files.createTempDirectory("github-source").toFile()
        try {
            root.resolve("main.java").writeText("source")
            val bytes = byteArrayOf(0, 1, -1)
            root.resolve("lib").mkdir()
            root.resolve("lib/plugin.dex").writeBytes(bytes)
            root.resolve("conversation.json").writeText("private conversation")
            root.resolve("plan.md").writeText("private development notes")
            MockWebServer().use { server ->
                server.json(repo)
                server.json("""{"object":{"sha":"parent"}}""")
                server.json("""{"sha":"source-blob"}""", 201)
                server.json("""{"sha":"dex-blob"}""", 201)
                server.json("""{"tree":{"sha":"base-tree"}}""")
                server.json("""{"sha":"new-tree"}""", 201)
                server.json("""{"sha":"new-commit"}""", 201)
                server.json("{}")
                val result = author(server).commitSource(root, "author/plugin", listOf("main.java", "lib/plugin.dex"), "Publish source", null)
                val requests = (0 until server.requestCount).map { server.takeRequest() }
                val uploaded = JSONObject(requests[3].body.readUtf8())
                assertArrayEquals(bytes, Base64.getDecoder().decode(uploaded.getString("content")))
                val tree = JSONObject(requests[5].body.readUtf8())
                assertEquals("base-tree", tree.getString("base_tree"))
                assertEquals(2, tree.getJSONArray("tree").length())
                assertFalse(tree.toString().contains("conversation"))
                assertFalse(tree.toString().contains("plan.md"))
                val commit = JSONObject(requests[6].body.readUtf8())
                assertEquals("parent", commit.getJSONArray("parents").getString(0))
                assertEquals("PATCH", requests[7].method)
                assertFalse(JSONObject(requests[7].body.readUtf8()).getBoolean("force"))
                assertEquals("new-commit", result.getString("sourceCommit"))
                assertEquals("https://github.com/author/plugin/tree/new-commit", result.getString("sourceUrl"))
                assertTrue(requests.all { it.getHeader("Authorization") == "Bearer fixture-token" })
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun emptyRepositoryBootstrapsThroughContentsBeforeUsingGitDatabase() {
        val root = Files.createTempDirectory("github-empty").toFile()
        try {
            root.resolve("main.java").writeText("source")
            MockWebServer().use { server ->
                server.json(repo)
                server.json("""{"message":"Git Repository is empty."}""", 409)
                server.json("""{"commit":{"sha":"initial"}}""", 201)
                server.json("""{"sha":"blob"}""", 201)
                server.json("""{"tree":{"sha":"base"}}""")
                server.json("""{"sha":"tree"}""", 201)
                server.json("""{"sha":"commit"}""", 201)
                server.json("{}")
                author(server).commitSource(root, "author/plugin", listOf("main.java"), "Publish", null)
                val requests = (0 until server.requestCount).map { server.takeRequest() }
                assertEquals("PUT", requests[2].method)
                assertEquals("/repos/author/plugin/contents/main.java", requests[2].path)
                assertEquals("initial", JSONObject(requests[6].body.readUtf8()).getJSONArray("parents").getString(0))
                assertEquals("PATCH", requests.last().method)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun missingBranchStartsFromDefaultBranchWithoutReplacingIt() {
        val root = Files.createTempDirectory("github-branch").toFile()
        try {
            root.resolve("main.java").writeText("source")
            MockWebServer().use { server ->
                server.json(repo)
                server.json("{}", 404)
                server.json("""{"object":{"sha":"main-parent"}}""")
                server.json("""{"sha":"blob"}""", 201)
                server.json("""{"tree":{"sha":"base"}}""")
                server.json("""{"sha":"tree"}""", 201)
                server.json("""{"sha":"commit"}""", 201)
                server.json("{}", 201)
                author(server).commitSource(root, "author/plugin", listOf("main.java"), "Publish", "release/source")
                val requests = (0 until server.requestCount).map { server.takeRequest() }
                assertEquals("main-parent", JSONObject(requests[6].body.readUtf8()).getJSONArray("parents").getString(0))
                assertEquals("POST", requests.last().method)
                assertEquals("refs/heads/release/source", JSONObject(requests.last().body.readUtf8()).getString("ref"))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun concurrentPushFailureIsReportedWithoutRetryOrForcePush() {
        val root = Files.createTempDirectory("github-conflict").toFile()
        try {
            root.resolve("main.java").writeText("source")
            MockWebServer().use { server ->
                server.json(repo)
                server.json("""{"object":{"sha":"parent"}}""")
                server.json("""{"sha":"blob"}""", 201)
                server.json("""{"tree":{"sha":"base"}}""")
                server.json("""{"sha":"tree"}""", 201)
                server.json("""{"sha":"commit"}""", 201)
                server.json("""{"message":"Update is not a fast forward"}""", 422)
                val error = assertThrows(GitHubFailure::class.java) {
                    author(server).commitSource(root, "author/plugin", listOf("main.java"), "Publish", null)
                }
                assertEquals(422, error.status)
                assertEquals(7, server.requestCount)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun pathEscapeAndMissingFileCannotStartRemoteWrites() {
        val root = Files.createTempDirectory("github-paths").toFile()
        val outside = Files.createTempFile("github-outside", ".txt")
        try {
            Files.createSymbolicLink(root.resolve("escape").toPath(), outside)
            MockWebServer().use { server ->
                for (paths in listOf(listOf("../outside"), listOf("escape"), listOf("missing"), listOf(".git/config"))) {
                    assertThrows(IllegalArgumentException::class.java) { author(server).commitSource(root, "author/plugin", paths, "Publish", null) }
                }
                assertEquals(0, server.requestCount)
            }
        } finally { root.deleteRecursively(); Files.delete(outside) }
    }

    @Test fun userAuthorizedTokenIsAcceptedWithoutEchoingAmbiguousOrMissingSecrets() {
        val token = "ghp_" + "a".repeat(36)
        assertEquals(token, GitHubClient.tokenFromMessage("我是作者，请用这个 $token 提交"))
        assertEquals(token, GitHubClient.tokenFromMessage("$token\n$token"))
        val other = "github_pat_" + "b".repeat(50)
        val error = assertThrows(IllegalArgumentException::class.java) { GitHubClient.tokenFromMessage("$token $other") }
        assertFalse(error.message!!.contains(token))
        assertThrows(IllegalArgumentException::class.java) { GitHubClient.tokenFromMessage("no token here") }
    }

    @Test fun tokenFromQuestionAnswerRemainsUsableWhenUserSaysUseTheOneIAlreadySent() {
        val older = "ghp_" + "a".repeat(36)
        val token = "ghp_" + "b".repeat(36)
        val messages = listOf(1L to "用这个 $older", 4L to "你就用我发你的那个")
        val answers = listOf(3L to "我就是作者 $token")
        assertEquals(token, GitHubClient.tokenFromUserHistory(messages + answers))
        assertThrows(IllegalArgumentException::class.java) { GitHubClient.tokenFromUserHistory(listOf(1L to "没有令牌")) }
    }
}
