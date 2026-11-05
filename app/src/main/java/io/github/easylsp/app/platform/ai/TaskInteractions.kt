package io.github.easylsp.app.platform.ai

import io.github.easylsp.app.data.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One interaction boundary shared by execution engines and Android tools. */
class TaskInteractions(private val dao: WorkflowDao) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    suspend fun request(conversationId: String, turnId: String, kind: String, request: JSONObject, accepted: (String) -> Unit = {}): JSONObject {
        val row = ChatInteractionEntity(UUID.randomUUID().toString(), conversationId, turnId, kind, request.toString())
        val answer = CompletableDeferred<JSONObject>()
        pending[row.id] = answer
        dao.saveInteraction(row)
        accepted(row.id)
        try { return answer.await() }
        finally {
            pending.remove(row.id)
            withContext(NonCancellable) {
                val saved = requireNotNull(dao.interaction(row.id))
                if (saved.status == "PENDING") dao.saveInteraction(saved.copy(status = "INTERRUPTED"))
            }
        }
    }
    fun answer(id: String, response: JSONObject) {
        val answer = pending[id] ?: error("此提问所属任务已结束")
        val row = requireNotNull(dao.interaction(id))
        dao.saveInteraction(row.copy(response = response.toString(), status = "ANSWERED"))
        answer.complete(response)
    }
}
