package io.github.easylsp.app

import io.github.easylsp.app.platform.harness.HarnessConnection
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HarnessConnectionTest {
    @Test fun authenticatesMatchesResponsesAndNeverResendsAfterDisconnect() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        val server = MockWebServer()
        var requests = 0
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                requests++
                val frame = JSONObject(text)
                when (frame.getString("method")) {
                    "host/status" -> {
                        webSocket.send("{\"method\":\"session.status\",\"params\":{\"status\":\"running\"}}")
                        webSocket.send(JSONObject().put("id", frame.getLong("id")).put("result", JSONObject().put("status", "running")).toString())
                    }
                    "session/prompt" -> { waiting.complete(Unit); webSocket.close(1000, "fixture disconnect after dispatch") }
                }
            }
        }))
        server.start()
        try {
            HarnessConnection.connect(server.port, "fixture-auth").use { client ->
                assertEquals("Bearer fixture-auth", server.takeRequest().getHeader("Authorization"))
                assertEquals("running", client.request("host/status").getString("status"))
                assertEquals("session.status", client.notifications.receive().getString("method"))
                val input = async { runCatching { client.request("session/prompt") } }
                waiting.await()
                assertTrue(withTimeout(5000) { input.await() }.isFailure)
                assertTrue(runCatching { client.request("session/prompt") }.isFailure)
                assertEquals(2, requests)
            }
        } finally { server.shutdown() }
    }
}
