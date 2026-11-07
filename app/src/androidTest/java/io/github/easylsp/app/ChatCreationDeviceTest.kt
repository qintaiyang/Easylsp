package io.github.easylsp.app

import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.feature.chat.ChatViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatCreationDeviceTest {
    @get:Rule val visible = VisibleActivityRule()

    @Test fun mainThreadCreationReadsRoomOffThreadAndReturnsToUi() = runBlocking<Unit>(Dispatchers.IO) {
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
        ActivityScenario.launch(MainActivity::class.java).use {
            val project = requireNotNull(graph.store.dao.projectByPackage("io.github.easylsp.demo", 0))
            val model = withTimeout(10000) { graph.models.available().first { it.isNotEmpty() }.first() }
            val vm = withContext(Dispatchers.Main) { ChatViewModel(graph) }
            val created = CompletableDeferred<String>()
            var createdId: String? = null
            val failure = launch { vm.error.first { it.isNotEmpty() && it != "previous failure" }.let { created.completeExceptionally(AssertionError(it)) } }
            try {
                withContext(Dispatchers.Main) {
                    vm.error.value = "previous failure"
                    vm.create(project, model) { id ->
                        createdId = id
                        if (Looper.myLooper() == Looper.getMainLooper()) created.complete(id)
                        else created.completeExceptionally(AssertionError("Navigation callback must run on main thread"))
                    }
                }
                val id = withTimeout(90000) { created.await() }
                val row = requireNotNull(graph.store.workflows.conversation(id))
                assertEquals(project.id, row.projectId)
                assertEquals(project.sampleRevision, row.sampleRevision)
                assertEquals(model.id, row.modelConfigId)
                assertEquals("", vm.error.value)
                assertTrue(graph.store.workflows.historyPage(id, 0, 1).isEmpty())
            } finally {
                failure.cancelAndJoin()
                createdId?.let { graph.store.workflows.deleteConversation(project.id, it) }
            }
        }
    }
}
