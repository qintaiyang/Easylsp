package io.github.easylsp.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.widget.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.platform.tools.ToolPayload
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class Phase6PhoneDeviceTest {
    @get:Rule val visible = VisibleActivityRule()
    @Test fun actualNodesUnicodeScreenshotWindowChangeAndOwnership() = runBlocking(Dispatchers.IO) {
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val owner = UUID.randomUUID().toString()
        lateinit var field: EditText
        lateinit var button: Button
        activity.onActivity { host ->
            host.setContentView(LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                field = EditText(host).apply { hint = "手机测试输入框"; contentDescription = "phone-fixture-input" }
                button = Button(host).apply { text = "确认输入"; setOnClickListener { text = "已确认：${field.text}" } }
                addView(field); addView(button)
            })
        }
        suspend fun use(action: String, args: JSONObject = JSONObject()) = graph.phone.use(owner, true, args.put("action", action))
        suspend fun observe() = use("observe", JSONObject().put("mode", "nodes").put("limit", 100))
        try {
            withTimeout(10000) { while (!graph.phone.status().getBoolean("enabled")) delay(100) }
            delay(700)
            val nodes = observe()
            val array = nodes.getJSONArray("nodes")
            val input = (0 until array.length()).map { array.getJSONObject(it) }.first { it.optBoolean("editable") }
            use("set_text", JSONObject().put("snapshotId", nodes.getString("snapshotId")).put("nodeId", input.getString("id")).put("text", "中文输入验证 你好 Android"))
            activity.onActivity { assertEquals("中文输入验证 你好 Android", field.text.toString()) }
            val afterText = observe()
            val tree = afterText.getJSONArray("nodes")
            val submit = (0 until tree.length()).map { tree.getJSONObject(it) }.first { it.optString("text") == "确认输入" }
            use("tap", JSONObject().put("snapshotId", afterText.getString("snapshotId")).put("target", JSONObject().put("nodeId", submit.getString("id"))))
            activity.onActivity { assertEquals("已确认：中文输入验证 你好 Android", button.text.toString()) }
            val image = use("observe", JSONObject().put("mode", "both")) as ToolPayload
            assertEquals("image", image.response.getJSONArray("content").getJSONObject(1).getString("type"))
            assertTrue(image.getJSONObject("image").getInt("width") > 0)
            val conflict = runCatching { graph.phone.use("other-owner", false, JSONObject().put("action", "observe")) }.exceptionOrNull()
            assertTrue(conflict?.message.orEmpty().startsWith("DEVICE_BUSY"))
            activity.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            delay(1000)
            val rotated = use("observe", JSONObject().put("mode", "both")) as ToolPayload
            assertEquals(rotated.getJSONObject("coordinateSpace").getInt("width"), rotated.getJSONObject("image").getInt("width"))
            assertTrue(rotated.getJSONObject("image").getInt("width") > rotated.getJSONObject("image").getInt("height"))
            use("launch", JSONObject().put("packageName", "com.android.settings"))
            delay(800)
            val otherApp = observe()
            assertTrue(otherApp.getString("packageName").contains("settings"))
            val stale = runCatching { use("tap", JSONObject().put("snapshotId", afterText.getString("snapshotId")).put("target", JSONObject().put("nodeId", submit.getString("id")))) }.exceptionOrNull()
            assertTrue(stale?.message.orEmpty().startsWith("STALE_OBSERVATION"))
            use("key", JSONObject().put("key", "back"))
            val waiter = async { use("wait", JSONObject().put("condition", "text_visible").put("text", "not-present-${UUID.randomUUID()}").put("timeoutMs", 10000)) }
            delay(200); graph.phone.release(owner)
            try { waiter.await(); fail("wait must be cancelled") } catch (_: CancellationException) { }
            assertTrue(graph.phone.status().isNull("ownerConversationId"))
        } finally { graph.phone.release(owner); activity.close() }
    }
}
