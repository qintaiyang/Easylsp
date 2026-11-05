package io.github.easylsp.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Reattach a recorded production conversation without sending another model request. */
class HarnessRecoveryDeviceTest {
    @get:Rule val visible = VisibleActivityRule()
    @Test fun restoreRecordedConversationWithoutResubmitting() = runBlocking(Dispatchers.IO) {
        val id = requireNotNull(InstrumentationRegistry.getArguments().getString("conversationId"))
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
        ActivityScenario.launch(MainActivity::class.java).use {
            graph.chat.observe(id).await()
            val mapping = requireNotNull(graph.store.workflows.harnessSession(id))
            val host = graph.chat.executionService()
            val params = JSONObject().put("sessionId", mapping.sessionId)
            val state = host.request("session/state", params)
            assertEquals("idle", state.getString("status"))
            val history = host.request("session/history", params.put("after", -1).put("limit", 200))
            assertEquals(history.getLong("cursor"), graph.store.workflows.harnessSession(id)!!.lastSeq)
            assertTrue(graph.store.workflows.turnHistory(id).all { turn -> turn.finishedAt != null })
            val before = graph.store.workflows.history(id).map { row -> row.engineMessageId }
            host.reconnect()
            assertEquals(before, graph.store.workflows.history(id).map { row -> row.engineMessageId })
            assertEquals("执行服务已连接", graph.harness.serviceStatus.value)
        }
    }
}
