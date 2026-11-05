package io.github.easylsp.app

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.*
import io.github.easylsp.app.platform.analysis.AnalysisEngine
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class AnalysisWorkflowTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val suffix = UUID.randomUUID().toString()
    private val store = ProjectStore(context, "analysis-$suffix.db", File(context.cacheDir, suffix))
    private var projectId: String? = null
    @After fun close() { store.close(); context.deleteDatabase("analysis-$suffix.db"); File(context.cacheDir, suffix).deleteRecursively(); projectId?.let { File(context.filesDir, "samples/$it").deleteRecursively() } }
    @Test fun importedDemoSearchesDecompilesAndPreservesSampleRevisions() = runBlocking(Dispatchers.IO) {
        val apk = File(context.packageManager.getApplicationInfo("io.github.easylsp.demo", 0).sourceDir)
        val samples = SampleStore(context, store)
        val first = samples.importApk(Uri.fromFile(apk)); projectId = first.id
        val engine = AnalysisEngine(); val sample = requireNotNull(store.dao.sample(first.id, 1))
        val hits = engine.execute(sample, "strings", JSONObject().put("query", "EASYLSP_DEMO_ADDITION")) {}.getJSONArray("items")
        assertTrue(hits.length() > 0)
        val methods = engine.execute(sample, "methods", JSONObject().put("owner", "io.github.easylsp.demo.Calculator").put("query", "add(II)I")) {}.getJSONArray("items")
        val method = methods.getJSONObject(0); assertEquals("Lio/github/easylsp/demo/Calculator;->add(II)I", method.getString("signature"))
        val args = JSONObject().put("className", method.getString("owner")).put("signature", method.getString("signature"))
        assertTrue(engine.execute(sample, "java", args) {}.getString("text").contains("add("))
        assertTrue(engine.execute(sample, "smali", args) {}.getString("text").contains("add(II)I"))
        assertTrue(engine.execute(sample, "manifest", JSONObject()) {}.getString("text").contains("manifest"))
        val second = samples.importApk(Uri.fromFile(apk)); assertEquals(first.id, second.id); assertEquals(2L, second.sampleRevision)
        assertEquals(1L, engine.execute(sample, "info", JSONObject()) {}.getLong("sampleRevision"))
        val next = requireNotNull(store.dao.sample(second.id, 2))
        assertEquals(2L, engine.execute(next, "info", JSONObject()) {}.getLong("sampleRevision"))
        engine.execute(next, "clear", JSONObject()) {}
        Unit
    }
}
