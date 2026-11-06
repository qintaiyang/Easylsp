package io.github.easylsp.app

import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OfflineReleaseDeviceTest {
    private fun archive(catalog: String, release: String, version: Int, source: String = "void onLoad() {}") = ByteArrayOutputStream().also { bytes ->
        val manifest = JSONObject("""{"formatVersion":3,"publisherId":"device-test","versionName":"1","name":"Fixture","description":"fixture","license":"MIT","sdkVersion":"v1","channel":"test","publishedAt":"2026-11-06T00:00:00Z","sourceUrl":"https://example.com/source","changelog":"initial","runtime":"BEANSHELL","pluginAbi":1,"minHostVersion":9,"requiredCapabilities":["java.beanshell"],"target":{"packageName":"fixture.demo","processScope":"main"},"artifacts":[{"abi":"any","minApi":31,"artifactPath":"java/main.java"}]}""")
            .put("catalogPluginId", catalog).put("releaseId", release).put("versionCode", version)
        ZipOutputStream(bytes).use { zip -> linkedMapOf("manifest.json" to manifest.toString(), "README.md" to "Fixture", "LICENSE" to "MIT fixture", "java/main.java" to source).forEach { (path, text) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
    }.toByteArray()
    @Test fun stableIdentityImmutableCacheRollbackAndLocalCopy() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = UUID.randomUUID().toString()
        val name = "release-$identity.db"
        val directory = File(context.cacheDir, name)
        val store = ProjectStore(context, name, directory)
        val project = ProjectEntity(identity, "fixture.demo", "Fixture", "1", kind = "IMPORTED")
        try {
            store.dao.insertProject(project)
            val first = store.importPlugin(project, archive(identity, "one", 1).inputStream())
            assertFalse(first.desiredEnabled)
            assertEquals(first.id, store.importPlugin(project, archive(identity, "one", 1).inputStream()).id)
            store.enable(first.id, true)
            val second = store.importPlugin(project, archive(identity, "two", 2, "void onLoad() { log(\"two\"); }").inputStream())
            assertEquals(first.id, second.id); assertTrue(second.desiredEnabled)
            try { store.importPlugin(project, archive(identity, "two", 2, "changed bytes").inputStream()); fail("immutable release replaced") }
            catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("releaseId")) }
            assertEquals("one", store.installCachedRelease(project.id, identity, "one").releaseId)
            store.save(first.id, "Edited", "", "", "1", "local changes")
            try { store.importPlugin(project, archive(identity, "two", 2, "void onLoad() { log(\"two\"); }").inputStream()); fail("local edits overwritten") }
            catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("本地修改")) }
            val local = store.preserveLocalCopy(first.id)
            assertEquals("LOCAL", local.source); assertFalse(local.desiredEnabled)
            assertEquals("local changes", store.read(local.id))
            assertEquals("void onLoad() {}", store.read(first.id))
            assertTrue(store.dao.plugin(first.id)!!.desiredEnabled)
            assertEquals(2, store.cachedReleases(first.id).size)
        } finally {
            store.close(); context.deleteDatabase(name); directory.deleteRecursively()
            File(context.filesDir, "release-cache/$identity").deleteRecursively()
        }
    }
}
