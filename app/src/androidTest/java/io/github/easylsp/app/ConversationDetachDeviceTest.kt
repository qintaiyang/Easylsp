package io.github.easylsp.app

import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.*
import io.github.easylsp.app.platform.harness.HarnessSessionClient
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ConversationDetachDeviceTest {
    @Test fun deletionWaitsForProjectionAndRejectsLateFrames() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "detach-${UUID.randomUUID()}.db"
        val directory = File(context.cacheDir, name)
        val store = ProjectStore(context, name, directory)
        try {
            store.workflows.insertConversation(ConversationEntity("chat", null, "provider", "model", "Fixture", 0, 1000, scope = "GLOBAL"))
            store.workflows.saveHarnessSession(HarnessSessionEntity("chat", "engine"))
            val release = CompletableDeferred<Unit>()
            var requests = 0
            val client = HarnessSessionClient(store, "chat") { _, _ ->
                requests++; release.await(); JSONObject().put("status", "detached")
            }
            val sync = async(start = CoroutineStart.UNDISPATCHED) { client.synchronize() }
            val detach = async(start = CoroutineStart.UNDISPATCHED) { client.detach() }
            assertFalse(detach.isCompleted)
            release.complete(Unit); sync.await(); detach.await()
            store.workflows.removeConversation("chat")
            client.accept(JSONObject().put("method", "session.event"))
            client.synchronize()
            assertEquals(1, requests)
            assertNull(store.workflows.conversation("chat"))
            assertTrue(store.workflows.history("chat").isEmpty())
        } finally { store.close(); context.deleteDatabase(name); directory.deleteRecursively() }
    }
}
