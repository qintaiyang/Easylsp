package io.github.easylsp.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Explicit host install/reboot boundary; caller supplies the expected external state. */
class NativeHostStatusDeviceTest {
    @Test fun installedAndRunningVersionsReflectPendingUpdate() = runBlocking(Dispatchers.IO) {
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
        val expected = InstrumentationRegistry.getArguments().getString("expectedStatus") ?: "COMPANION_LOADED"
        val status = graph.nativeHost.refresh()
        assertEquals(status.toString(), expected, status.getString("runtimeStatus"))
        assertEquals(expected == "PENDING_REBOOT", status.optBoolean("pendingHostUpdate"))
        assertTrue(status.getString("installedRuntime").contains("2.0.1"))
        File(graph.filesDir, "native-host-status-$expected.json").writeText(status.toString(2))
    }
}
