package io.github.easylsp.app

import android.app.NotificationManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.*
import io.github.easylsp.app.platform.diagnostics.DiagnosticService
import io.github.easylsp.app.util.readUpTo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Runs the same API31 package on the minimum system and a newer system. */
class AndroidPlatformCompatibilityDeviceTest {
    @get:org.junit.Rule(order = 0) val visible = VisibleActivityRule()
    @get:org.junit.Rule(order = 1) val activity = androidx.test.ext.junit.rules.ActivityScenarioRule(MainActivity::class.java)

    @Test fun foregroundServiceArchivesAndHistoricalArtifactCompatibility() = runBlocking<Unit>(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph = instrumentation.targetContext.applicationContext as EasylspApp
        val evidence = JSONObject().put("sdk", Build.VERSION.SDK_INT).put("model", Build.MODEL)
        assertTrue(graph.root.useRoot())
        val framework = withTimeout(15000) { graph.framework.state.first { it.compatible } }
        evidence.put("rootGranted", true).put("frameworkName", framework.frameworkName)
            .put("frameworkVersion", framework.frameworkVersion).put("frameworkApi", framework.apiVersion)
        val host = graph.nativeHost.refresh()
        assertEquals(host.toString(), "COMPANION_LOADED", host.getString("runtimeStatus"))
        evidence.put("host", host)
        val modelId = graph.models.defaultModel(null)
        evidence.put("configuredModelCredentialReadable", modelId?.let { graph.models.secret(it).isNotEmpty() } ?: JSONObject.NULL)
        val manager = graph.getSystemService(NotificationManager::class.java)
        try {
            DiagnosticService.start(graph)
            withTimeout(5000) { while (manager.activeNotifications.none { it.id == 32 }) delay(50) }
            evidence.put("diagnosticForegroundNotification", true)
        } finally { DiagnosticService.finish(graph) }

        val currentBytes = instrumentation.context.assets.open("fixed-native/early-symbol-A/plugin-v3.zip").use { it.readUpTo(32 * 1024 * 1024 + 1) }
        val current = PluginArchive.read(currentBytes.inputStream())
        assertEquals(31, PluginRelease.read(current.getValue("manifest.json")).artifacts.getJSONObject(0).getInt("minApi"))
        val oldBytes = instrumentation.context.assets.open("compatibility/api34-release.zip").use { it.readBytes() }
        val old = PluginArchive.read(oldBytes.inputStream())
        assertEquals(34, PluginRelease.read(old.getValue("manifest.json")).artifacts.getJSONObject(0).getInt("minApi"))
        evidence.put("api31ElfAccepted", true).put("api34ElfParsedWithOriginalDeclaration", true)

        val identity = UUID.randomUUID().toString()
        val database = "compatibility-$identity.db"
        val directory = File(graph.cacheDir, "compatibility-$identity")
        val store = ProjectStore(graph, database, directory)
        val project = ProjectEntity(identity, "io.github.easylsp.demo", "Compatibility fixture", "1", kind = "IMPORTED")
        try {
            store.dao.insertProject(project)
            if (Build.VERSION.SDK_INT >= 34) {
                val historical = store.importPlugin(project, oldBytes.inputStream())
                assertEquals(34, store.nativePlugins.builds(historical.id).single().minApi)
                evidence.put("api34Install", "ACCEPTED")
            }
            val installed = store.importPlugin(project, currentBytes.inputStream())
            assertTrue(store.nativePlugins.builds(installed.id).any { it.minApi == 31 })
            if (Build.VERSION.SDK_INT < 34) {
                val failure = runCatching { store.importPlugin(project, oldBytes.inputStream()) }.exceptionOrNull()
                assertTrue(failure.toString(), failure is IllegalArgumentException && failure.message.orEmpty().contains("API 34"))
                evidence.put("api34Install", "REJECTED_WITH_PLATFORM_REASON")
            }
            val archive = File(directory, "source.zip").apply { parentFile!!.mkdirs() }
            archive.outputStream().use { store.exportPlugin(installed.id, it) }
            val source = archive.inputStream().use(PluginArchive::read)
            assertTrue(source.getValue("info.prop").toString(Charsets.UTF_8).contains("minApi=31"))
            assertArrayEquals(current.getValue("LICENSE"), source.getValue("LICENSE"))
            val restored = archive.inputStream().use { store.importPlugin(project, it) }
            assertEquals(store.read(installed.id), store.read(restored.id))
            evidence.put("api31SourceRoundTrip", true)
            File(graph.filesDir, "android-platform-compatibility.json").writeText(evidence.toString(2))
        } finally {
            store.close(); graph.deleteDatabase(database); directory.deleteRecursively()
            File(graph.filesDir, "workspaces/$identity").deleteRecursively()
        }
    }
}
