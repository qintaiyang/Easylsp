package io.github.easylsp.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ManagerKeepaliveDeviceTest {
    @get:org.junit.Rule(order=0) val visible=VisibleActivityRule()
    @get:org.junit.Rule(order=1) val activity=androidx.test.ext.junit.rules.ActivityScenarioRule(MainActivity::class.java)
    @Test fun applicationFlowControlsRealUidZeroKeeper() = runBlocking<Unit>(Dispatchers.IO) {
        val graph=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
        check(graph.root.useRoot())
        val before=graph.managerKeepalive.state.value.enabled
        suspend fun root(script: String): String {
            val output=StringBuilder()
            val result=graph.executions.runner.execute(UUID.randomUUID().toString(),listOf(graph.root.su,"-c",script),graph.filesDir,closeInput=true,timeoutSeconds=30) { output.append(it) }
            check(result.exitCode==0) { output.toString() };return output.toString().trim()
        }
        try {
            if (before) withTimeout(15000) { graph.managerKeepalive.state.first { it.running && it.managerPid == android.os.Process.myPid() } }
            val active=graph.managerKeepalive.setEnabled(true)
            assertTrue(active.running);assertEquals(0,active.keeperUid);assertEquals(-1000,active.oomScoreAdj)
            assertEquals(android.os.Process.myPid(),active.managerPid);assertEquals("/",active.cgroup)
            assertNotEquals("error",active.binderStatus);assertNotEquals("error",active.hansStatus)
            val identity=root("awk '/^Uid:/ {print ${'$'}2}' /proc/${active.keeperPid}/status")
            assertEquals("0",identity)
            val same=graph.managerKeepalive.reconcile()
            assertEquals(active.keeperPid,same.keeperPid)
            val stopped=graph.managerKeepalive.setEnabled(false)
            assertFalse(stopped.running)
            assertNotEquals("-1000",root("cat /proc/${android.os.Process.myPid()}/oom_score_adj"))
            val restarted=graph.managerKeepalive.setEnabled(true)
            assertTrue(restarted.running);assertNotEquals(active.keeperPid,restarted.keeperPid)
            File(graph.filesDir,"manager-keepalive-evidence.json").writeText(JSONObject().put("status","PASSED").put("keeperUid",restarted.keeperUid).put("keeperPid",restarted.keeperPid).put("managerPid",restarted.managerPid).put("managerUid",android.os.Process.myUid()).put("oomScoreAdj",restarted.oomScoreAdj).put("binder",restarted.binderStatus).put("hans",restarted.hansStatus).put("automaticStartup",before).put("manufacturer",android.os.Build.MANUFACTURER).put("vendorSettings",io.github.easylsp.app.platform.root.ManufacturerBackgroundSettings.autostart(graph).toUri(android.content.Intent.URI_INTENT_SCHEME)).put("idempotentStart",true).put("disableRestoresOom",true).put("reEnable",true).toString(2))
        } finally { graph.managerKeepalive.setEnabled(before) }
    }
}
