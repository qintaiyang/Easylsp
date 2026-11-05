package io.github.easylsp.app
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.platform.analysis.runDependencyProbe
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
class DependencyProbeTest {
    @Test fun androidIntegrationOnInstalledDemo() = runBlocking {
        val result = runDependencyProbe(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue(result.getString("message"), result.getBoolean("success"))
        val report = result.getString("message")!!
        assertTrue(report.contains("BeanShell:")); assertTrue(report.contains("DexKit:")); assertTrue(report.contains("JADX:"))
    }
}
