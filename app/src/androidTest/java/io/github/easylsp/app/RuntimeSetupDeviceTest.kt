package io.github.easylsp.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.platform.network.RuntimeSources
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class RuntimeSetupDeviceTest {
    @get:Rule val visible = VisibleActivityRule()

    @Test fun selectsSourcesAndPreparesRealRuntime() = runBlocking(Dispatchers.IO) {
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
        ActivityScenario.launch(MainActivity::class.java).use {
            val log = File(graph.filesDir, "runtime-setup-device.log").apply { writeText("") }
            val record: (String) -> Unit = { text -> synchronized(log) { log.appendText(text) } }
            val sources = RuntimeSources()
            sources.fastest("Node", RuntimeSources.node, record)
            sources.fastest("Ubuntu", RuntimeSources.ubuntu, record)
            graph.harness.prepareAndWait()
            assertTrue(graph.linux.installed)
            assertTrue(graph.harness.installed)
            assertNotEquals("stopped", graph.harness.refresh().getString("status"))
            record("AI 环境安装和启动通过\n")
        }
    }
}
