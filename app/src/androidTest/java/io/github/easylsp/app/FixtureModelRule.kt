package io.github.easylsp.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.rules.ExternalResource

/** Identity for deterministic protocol/UI fixtures, never evidence of a real model run. */
class FixtureModelRule : ExternalResource() {
    private val graph get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
    lateinit var id: String; private set
    private lateinit var providerId: String
    override fun before() = runBlocking<Unit>(Dispatchers.IO) {
        val provider = graph.models.configureProvider(null, JSONObject().put("name", "临时验收夹具").put("protocol", "openai").put("baseUrl", "http://127.0.0.1:9"))
        providerId = provider.id
        id = graph.models.configureModel(null, provider.id, "fixture", JSONObject().put("contextWindow", 32768).put("maxOutputTokens", 1024)).id
    }
    override fun after() = runBlocking<Unit>(Dispatchers.IO) {
        graph.openOrCreateDatabase("easylsp.db", 0, null).use { it.delete("provider_models", "id = ?", arrayOf(id)) }
        graph.store.workflows.deleteProvider(providerId)
    }
}
