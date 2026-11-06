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

/** Isolated database and unique cache identity; no root publication or user data edits. */
class M7ReleaseDeviceTest {
    @Test fun jointInstallRollbackTransactionAndSourceIsolation() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = UUID.randomUUID().toString()
        val database = "m7-$id.db"
        val directory = File(context.cacheDir,database)
        val store = ProjectStore(context,database,directory)
        val project = ProjectEntity(id,"fixture.demo","M7","1",kind="IMPORTED")
        val native = instrumentation.context.assets.open("fixed-native/early-symbol-A/plugin.so").use { it.readBytes() }
        fun archive(release: String, version: Int): ByteArray {
            val manifest=JSONObject("""{"formatVersion":3,"publisherId":"m7","versionName":"1","name":"Joint","description":"fixture","license":"MIT","sdkVersion":"p0-v4-api31-m7","channel":"test","publishedAt":"2026-11-06T00:00:00Z","sourceUrl":"https://example.com/source","changelog":"fixture","runtime":"JAVA_NATIVE","pluginAbi":1,"minHostVersion":9,"requiredCapabilities":["release.joint"],"target":{"packageName":"fixture.demo","processScope":"main"},"artifacts":[{"abi":"arm64-v8a","minApi":31,"artifactPath":"native/arm64-v8a/plugin.so"},{"abi":"any","minApi":31,"artifactPath":"java/main.java"}]}""")
                .put("catalogPluginId",id).put("releaseId",release).put("versionCode",version)
            return ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
                mapOf("manifest.json" to manifest.toString().toByteArray(),"README.md" to "M7".toByteArray(),"LICENSE" to "MIT fixture".toByteArray(),"native/arm64-v8a/plugin.so" to native,"java/main.java" to "void onLoad() {}".toByteArray()).forEach { (path,bytes) -> zip.putNextEntry(ZipEntry(path));zip.write(bytes);zip.closeEntry() }
            } }.toByteArray()
        }
        try {
            store.dao.insertProject(project)
            val first=store.importPlugin(project,archive("one",1).inputStream())
            val rows=store.dao.plugins(id)
            assertEquals(setOf("NATIVE","BEANSHELL"),rows.map { it.runtime }.toSet())
            assertEquals(2,rows.size)
            val java=rows.single { it.runtime=="BEANSHELL" }
            store.dao.updatePlugin(java.copy(marketVersionCode=99))
            try { store.importPlugin(project,archive("two",2).inputStream());fail("partial joint update accepted") } catch (_: IllegalArgumentException) {}
            assertEquals("one",store.dao.plugin(first.id)!!.releaseId)
            assertEquals("one",store.dao.plugin(java.id)!!.releaseId)
            store.dao.updatePlugin(java)
            val updated=store.importPlugin(project,archive("two",2).inputStream())
            assertEquals(first.id,updated.id)
            assertTrue(store.dao.plugins(id).all { it.releaseId=="two" })
            store.installCachedRelease(id,id,"one")
            assertTrue(store.dao.plugins(id).all { it.releaseId=="one" })
            val third=store.importPlugin(project,archive("one",1).inputStream(),marketSourceId="m7fixture")
            assertNotEquals(first.id,third.id)
            assertEquals(4,store.dao.plugins(id).size)
            assertNotEquals(store.releaseCache(id,"one"),store.releaseCache(id,"one","m7fixture"))
            store.deleteRelease(third.id)
            assertEquals(2,store.dao.plugins(id).size)
        } finally {
            store.close();context.deleteDatabase(database);directory.deleteRecursively()
            File(context.filesDir,"release-cache/$id").deleteRecursively()
            File(context.filesDir,"release-cache-sources/m7fixture/$id").deleteRecursively()
            File(context.filesDir,"workspaces/$id").deleteRecursively()
        }
    }
}
