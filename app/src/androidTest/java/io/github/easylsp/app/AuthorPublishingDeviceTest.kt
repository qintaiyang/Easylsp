package io.github.easylsp.app

import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.ProjectEntity
import io.github.easylsp.app.platform.tools.ReleaseTools
import io.github.easylsp.app.platform.tools.RunBinding
import io.github.easylsp.app.platform.tools.ToolArguments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Explicit public-write acceptance; invoked only with a separately staged private author credential. */
class AuthorPublishingDeviceTest {
    @get:org.junit.Rule(order = 0) val visible = VisibleActivityRule()
    @get:org.junit.Rule(order = 1) val activity = androidx.test.ext.junit.rules.ActivityScenarioRule(MainActivity::class.java)
    @Test fun authorizedToolPreparationUploadResumeAndSubmission() = runBlocking(Dispatchers.IO) {
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
        val credential = File(graph.filesDir, "author-test-credential")
        require(credential.isFile) { "This public-write test requires an explicitly staged author credential" }
        val profile = try { graph.github.tokenLogin(credential.readText().trim()) } finally { credential.delete() }
        assertEquals("qintaiyang", profile.getString("login"))
        val project = ProjectEntity(UUID.randomUUID().toString(), "io.github.easylsp.demo", "Author tool fixture", "1")
        graph.store.dao.insertProject(project)
        try {
            val releases = graph.market.refresh().getJSONArray("releases")
            val original = (0 until releases.length()).map { releases.getJSONObject(it) }.single { it.getString("releaseId") == "demo-early-1" }
            val plugin = graph.market.install(project.id, original)
            val build = graph.store.nativePlugins.builds(plugin.id).single()
            val manifest = JSONObject(plugin.releaseManifest).put("releaseId", "demo-app-author-3").put("versionCode", 3).put("versionName", "1.0-app-author")
                .put("changelog", "Prepared, uploaded and submitted through the actual Android author services and AI tool definitions. Controlled Demo only.")
            val tools = ReleaseTools(graph).definitions(RunBinding(null, 0, null, UUID.randomUUID().toString())).associateBy { it.name }
            val prepareArgs = JSONObject().put("pluginId", plugin.id).put("buildId", build.buildId).put("manifest", manifest)
            val prepare = tools.getValue("prepare_plugin_release")
            assertTrue(prepare.writes); ToolArguments.validate(prepare.schema, prepareArgs)
            val prepared = prepare.invoke(prepareArgs)
            val id = prepared.getString("preparationId")
            val arguments = JSONObject().put("preparationId", id).put("repository", "qintaiyang/Easylsp-Plugins").put("sourceCommit", "6a583976fc0f4f07717d89c589bb109293128c00")
            val publish = tools.getValue("publish_plugin_release")
            assertTrue(publish.writes); assertEquals("release.github", publish.groupId)
            ToolArguments.validate(publish.schema, arguments)
            assertTrue(publish.preview(arguments).contains("qintaiyang/Easylsp-Plugins"))
            val uploaded = publish.invoke(arguments)
            assertEquals("UPLOADED", uploaded.getString("status"))
            assertEquals(uploaded.getLong("assetId"), publish.invoke(arguments).getLong("assetId"))
            val submit = tools.getValue("submit_market_release")
            assertTrue(submit.writes)
            val submitArgs = JSONObject().put("preparationId", id)
            val submitted = submit.invoke(submitArgs)
            assertEquals("PENDING_REVIEW", submitted.getString("status"))
            assertEquals(submitted.getInt("prNumber"), submit.invoke(submitArgs).getInt("prNumber"))
            val status = tools.getValue("get_market_submission_status")
            assertFalse(status.writes)
            assertEquals("PENDING_REVIEW", status.invoke(submitArgs).getString("status"))
            File(graph.filesDir, "author-publishing-device-evidence.json").writeText(submitted.toString(2))
        } finally {
            graph.github.logout()
            graph.store.dao.plugins(project.id).forEach { graph.store.delete(it.id) }
            graph.store.nativePlugins.deleteProjectBuilds(project.id)
            graph.store.dao.deleteProject(project.id)
            File(graph.filesDir, "workspaces/${project.id}").deleteRecursively()
        }
    }
}
