package io.github.easylsp.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Provisions the same pinned execution environment used by the app. */
class CompletionEnvironmentDeviceTest {
    @get:org.junit.Rule(order = 0) val visible = VisibleActivityRule()
    @get:org.junit.Rule(order = 1) val activity = androidx.test.ext.junit.rules.ActivityScenarioRule(MainActivity::class.java)
    @Test fun removeInterruptedAcceptanceProviders() = runBlocking<Unit>(Dispatchers.IO) {
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
        for (id in listOf("5547aa29-88e8-4f86-976f-036de8b5d060", "1161beca-c3ae-4353-88e7-4770c0511aca")) {
            val provider = graph.store.workflows.provider(id) ?: continue
            assertEquals("临时验收夹具", provider.name)
            graph.openOrCreateDatabase("easylsp.db", 0, null).use { it.delete("provider_models", "providerId = ?", arrayOf(id)) }
            graph.store.workflows.deleteProvider(id)
        }
    }
    @Test fun installPinnedHarnessAndProbe() = runBlocking(Dispatchers.IO) {
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
        assertTrue(graph.root.useRoot())
        if (!graph.linux.installed) graph.linux.install()
        if (!graph.harness.installed) graph.harness.install()
        try {
            val status = graph.harness.start()
            assertEquals(status.toString(), "empty", status.getString("status"))
        } finally { graph.harness.stop() }
    }
}
