package io.github.easylsp.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.*
import io.github.easylsp.app.platform.harness.HarnessExecutionService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Run with scripts/verify-harness-process-recovery.py: the fixture outlives Android. */
class HarnessProcessRecoveryDeviceTest {
    @get:Rule val visible = VisibleActivityRule()
    private val graph = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
    private val dao get() = graph.store.workflows
    private val marker get() = File(graph.filesDir, "phase3-process-recovery-ready.json")

    private fun fixture(port: Int, path: String) = JSONObject(URL("http://127.0.0.1:$port/$path").readText())

    @Test fun prepareForExternalProcessKill(): Unit = runBlocking(Dispatchers.IO) {
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val port = InstrumentationRegistry.getArguments().getString("fixturePort")!!.toInt()
        val project = graph.store.select("io.github.easylsp.demo", "Easylsp Demo", "1")
        val id = UUID.randomUUID().toString(); val provider = UUID.randomUUID().toString()
        dao.saveProvider(ProviderEntity(provider, "进程恢复 fixture", "openai", "http://127.0.0.1:$port/v1", "fixture", maxSteps = 0,
            timeoutSeconds = 120, options = "{\"contextWindow\":1000000,\"contextBudget\":200000}"))
        graph.secrets.put("provider:$provider", "fixture-key")
        dao.insertConversation(ConversationEntity(id, project.id, provider, "fixture", "进程恢复 fixture", project.sampleRevision, System.currentTimeMillis()))
        graph.chat.submit(id, "读取项目，进程恢复后再读取一次", null, true, JSONArray()).await()
        withTimeout(30000) {
            dao.toolRuns(id).first { it.singleOrNull()?.status == "COMPLETE" }
            while (fixture(port, "state").getInt("requests") < 2) delay(50)
        }
        marker.writeText(JSONObject().put("conversationId", id).put("providerId", provider).put("fixturePort", port)
            .put("androidPid", android.os.Process.myPid()).put("host", JSONObject(File(graph.filesDir, "harness/host.json").readText()))
            .put("bridgePort", JSONObject(File(graph.filesDir, "harness/bridge.json").readText()).getInt("port")).toString())
        delay(60000)
        activity.close()
        error("The driver must kill the prepared Android process")
    }

    @Test fun reconnectFinishAndCancelWithoutReplayingInput() = runBlocking(Dispatchers.IO) {
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val saved = JSONObject(marker.readText()); val id = saved.getString("conversationId")
        val port = saved.getInt("fixturePort"); val provider = saved.getString("providerId")
        val projectId = requireNotNull(dao.conversation(id)).projectId
        try {
            assertNotEquals(saved.getInt("androidPid"), android.os.Process.myPid())
            graph.chat.observe(id).await()
            assertEquals(saved.getJSONObject("host").getInt("pid"), JSONObject(File(graph.filesDir, "harness/host.json").readText()).getInt("pid"))
            assertEquals(saved.getInt("bridgePort"), JSONObject(File(graph.filesDir, "harness/bridge.json").readText()).getInt("port"))
            fixture(port, "release")
            val turn = withTimeout(60000) { dao.turns(id).first { it.singleOrNull()?.finishedAt != null } }.single()
            assertEquals(turn.errorDetail, "COMPLETE", turn.status)
            withTimeout(10000) { graph.chat.activeConversation.first { it == null } }
            val runs = dao.toolRuns(id).first()
            assertEquals(2, runs.size); assertTrue(runs.all { it.name == "get_project_info" && it.status == "COMPLETE" })
            assertEquals(2, runs.map { it.toolCallId }.distinct().size)
            assertEquals(1, dao.history(id).count { it.role == "user" })
            val messageIds = dao.history(id).map { it.engineMessageId }
            graph.chat.observe(id).await()
            assertEquals(messageIds, dao.history(id).map { it.engineMessageId })
            assertEquals(3, fixture(port, "state").getInt("requests"))

            // Measure warm protocol overhead without model latency or tool work.
            val bridgePort = saved.getInt("bridgePort")
            val token = graph.secrets.get("harness-bridge")
            val samples = List(30) { index ->
                val start = System.nanoTime()
                val connection = URL("http://127.0.0.1:$bridgePort/mcp").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write("{\"jsonrpc\":\"2.0\",\"id\":$index,\"method\":\"ping\"}".toByteArray()) }
                assertTrue(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).has("result"))
                connection.disconnect()
                (System.nanoTime() - start) / 1_000_000.0
            }.sorted()

            graph.chat.submit(id, "等待模型时取消", null, true, JSONArray()).await()
            withTimeout(10000) { while (fixture(port, "state").getInt("requests") < 4) delay(50) }
            graph.chat.stop()
            val cancelled = withTimeout(15000) { dao.turns(id).first { it.size == 2 && it.last().finishedAt != null } }.last()
            assertEquals("CANCELLED", cancelled.status)
            withTimeout(10000) { while (!fixture(port, "state").getBoolean("cancelledHttp")) delay(50) }
            assertEquals(2, dao.toolRuns(id).first().size)
            File(graph.filesDir, "phase3-process-recovery-evidence.json").writeText(JSONObject()
                .put("androidProcessKilled", true).put("hostPidPreserved", true).put("bridgePortPreserved", true)
                .put("originalInputCount", 1).put("toolsBeforeAndAfterRestart", 2).put("inputOrToolReplays", 0)
                .put("cancelledActualHttp", true).put("modelRequests", 4)
                .put("warmBridgePingSamples", 30).put("warmBridgePingMedianMillis", samples[14]).put("warmBridgePingP95Millis", samples[28])
                .put("status", "PASS").toString(2))
        } finally {
            graph.chat.stop()
            graph.harness.stop()
            graph.stopService(android.content.Intent(graph, HarnessExecutionService::class.java))
            graph.store.transaction {
                dao.deleteMessages(id); dao.deleteToolRuns(id); dao.deleteTurns(id); dao.deleteInputs(id); dao.deleteInteractions(id)
                dao.deleteConversation(requireNotNull(projectId), id); dao.deleteProvider(provider)
            }
            graph.secrets.delete("provider:$provider"); marker.delete(); activity.close()
        }
    }
}
