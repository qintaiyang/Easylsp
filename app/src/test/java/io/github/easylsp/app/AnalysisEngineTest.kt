package io.github.easylsp.app

import com.android.tools.smali.dexlib2.*
import com.android.tools.smali.dexlib2.immutable.*
import com.android.tools.smali.dexlib2.immutable.instruction.*
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import io.github.easylsp.app.data.SampleEntity
import io.github.easylsp.app.platform.analysis.AnalysisEngine
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files
import java.util.zip.*

class AnalysisEngineTest {
    private fun dex(owner: String, answer: Int): ByteArray {
        val implementation = ImmutableMethodImplementation(1, listOf(ImmutableInstruction31i(Opcode.CONST, 0, answer), ImmutableInstruction11x(Opcode.RETURN, 0)), emptyList(), emptyList())
        val method = ImmutableMethod(owner, "answer", emptyList<ImmutableMethodParameter>(), "I", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, emptySet(), emptySet(), implementation)
        val cls = ImmutableClassDef(owner, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", emptyList(), "Fixture.java", emptyList(), emptyList(), listOf(method))
        val pool = DexPool(Opcodes.getDefault()); pool.internClass(cls)
        val data = MemoryDataStore(); pool.writeTo(data); return data.data
    }
    @Test fun resourceQueriesNeverParseUnrelatedDex() = runBlocking {
        val dir = Files.createTempDirectory("easylsp-resource").toFile()
        val apk = java.io.File(dir, "resource.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            listOf("classes.dex" to "invalid dex", "assets/readme.txt" to "readable resource").forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
        }
        val sample = SampleEntity("resources", 1, JSONArray().put(apk.path).toString(), "1", 0)
        val engine = AnalysisEngine(jadx.api.security.impl.JadxSecurity(jadx.api.security.JadxSecurityFlag.all()))
        try {
            val listing = engine.execute(sample, "resources", JSONObject()) {}
            assertEquals(1, listing.getInt("total"))
            val text = engine.execute(sample, "resource", JSONObject().put("apk", "resource.apk").put("entry", "assets/readme.txt")) {}.getString("text")
            assertEquals("readable resource", text)
        } finally { engine.execute(sample, "clear", JSONObject()) {}; dir.deleteRecursively() }
        Unit
    }
    @Test fun multiDexQueriesJavaSmaliPagingAndRevisionSwitchUseRealDexFiles() = runBlocking {
        val dir = Files.createTempDirectory("easylsp-analysis").toFile()
        val apk = java.io.File(dir, "fixture.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            listOf("classes.dex" to dex("Lfixture/First;", 42), "classes2.dex" to dex("Lfixture/Second;", 7)).forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
        val sample = SampleEntity("fixture", 1, JSONArray().put(apk.path).toString(), "1", 0)
        val engine = AnalysisEngine(jadx.api.security.impl.JadxSecurity(jadx.api.security.JadxSecurityFlag.all()))
        try {
            val page1 = engine.execute(sample, "classes", JSONObject().put("query", "fixture").put("limit", 1)) {}
            assertEquals(2, page1.getInt("total")); assertEquals(1, page1.getInt("nextOffset"))
            val page2 = engine.execute(sample, "classes", JSONObject().put("query", "fixture").put("offset", 1).put("limit", 1)) {}
            assertTrue(page2.getJSONArray("items").getJSONObject(0).getString("source").contains("classes2.dex"))
            val methods = engine.execute(sample, "methods", JSONObject().put("owner", "fixture.First").put("query", "answer")) {}.getJSONArray("items")
            assertEquals("Lfixture/First;->answer()I", methods.getJSONObject(0).getString("signature"))
            val args = JSONObject().put("className", "fixture.First").put("signature", "Lfixture/First;->answer()I")
            val smali = engine.execute(sample, "smali", args) {}.getString("text")
            assertTrue(smali.contains("answer()I")); assertTrue(smali.contains("0x2a"))
            val progress = mutableListOf<String>()
            val java = engine.execute(sample, "java", args) { progress += it }.getString("text")
            assertTrue(java, java.contains("answer()")); assertTrue(java, java.contains("42"))
            assertTrue(progress.any { it.startsWith("初始化 JADX") })
            progress.clear()
            val nextPage = engine.execute(sample, "java", JSONObject(args.toString()).put("offset", 1).put("limit", 2)) { progress += it }
            assertEquals(java.lines().drop(1).take(2).joinToString("\n"), nextPage.getString("text"))
            assertTrue("Paging must reuse extracted/decompiled text", progress.none { it.startsWith("初始化 JADX") || it.startsWith("还原") })
            val second = sample.copy(revision = 2, version = "2")
            assertEquals(2L, engine.execute(second, "methods", JSONObject()) {}.getLong("sampleRevision"))
            engine.execute(second, "java", args) { progress += it }
            assertTrue("New sample must not reuse the old cache", progress.any { it.startsWith("初始化 JADX") })
        } finally { engine.execute(sample, "clear", JSONObject()) {}; dir.deleteRecursively() }
        Unit
    }
}
