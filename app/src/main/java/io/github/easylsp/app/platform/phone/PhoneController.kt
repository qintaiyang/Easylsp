package io.github.easylsp.app.platform.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.platform.tools.ToolResults
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PhoneController(private val graph: EasylspApp) {
    @Volatile private var service: PhoneAccessibilityService? = null
    @Volatile private var owner: String? = null
    @Volatile private var changedAt = SystemClock.uptimeMillis()
    @Volatile private var contentVersion = 0L
    private val serial = Mutex()
    private var active: Job? = null
    private data class Observation(val id: String, val owner: String, val displayId: Int, val rotation: Int, val width: Int, val height: Int,
        val window: Int, val version: Long, val nodes: List<AccessibilityNodeInfo>, val fingerprints: List<String>, val query: String, val createdAt: Long)
    private var observation: Observation? = null
    private val inputSchema by lazy { JSONObject(graph.assets.open("phone-use.input.schema.json").bufferedReader().use { it.readText() }) }
    private val keys = mapOf("back" to AccessibilityService.GLOBAL_ACTION_BACK, "home" to AccessibilityService.GLOBAL_ACTION_HOME,
        "recents" to AccessibilityService.GLOBAL_ACTION_RECENTS, "notifications" to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
        "quick_settings" to AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "power_dialog" to AccessibilityService.GLOBAL_ACTION_POWER_DIALOG,
        "lock_screen" to AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "screenshot" to AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
    fun connect(value: PhoneAccessibilityService) { service = value }
    fun changed() { changedAt = SystemClock.uptimeMillis(); contentVersion++ }
    @Synchronized fun disconnect() { service = null; active?.cancel(); active = null; owner = null; observation = null }
    @Synchronized fun release(conversationId: String) {
        if (owner == conversationId) { owner = null; observation = null; active?.cancel(); active = null }
    }
    @Synchronized private fun acquire(id: String) {
        check(owner == null || owner == id) { "DEVICE_BUSY: 手机由会话 $owner 使用" }; owner = id
    }
    fun status() = JSONObject().put("backend", "accessibility").put("enabled", service != null).put("ownerConversationId", owner ?: JSONObject.NULL)
        .put("actions", JSONArray(listOf("status", "release", "list_apps", "observe", "launch", "open_uri", "tap", "long_press", "swipe", "gesture", "set_text", "key", "wait")))
        .put("keys", JSONArray(keys.filterValues { id -> service?.systemActions?.any { it.id == id } == true }.keys + if (graph.root.state.value.granted) setOf("enter", "volume_up", "volume_down", "power") else emptySet())).put("screen", display(0).let { screen -> val metrics = android.util.DisplayMetrics(); screen.getRealMetrics(metrics); JSONObject().put("width", metrics.widthPixels).put("height", metrics.heightPixels).put("rotation", screen.rotation) }).put("gestureLimits", JSONObject().put("maxStrokes", GestureDescription.getMaxStrokeCount()).put("maxDurationMs", GestureDescription.getMaxGestureDuration()))
    suspend fun use(conversationId: String, supportsImages: Boolean, args: JSONObject): JSONObject {
        val action = args.getString("action")
        val branches = inputSchema.getJSONArray("oneOf")
        val schema = (0 until branches.length()).map { branches.getJSONObject(it) }.firstOrNull { it.getJSONObject("properties").getJSONObject("action").getString("const") == action } ?: error("CAPABILITY_UNAVAILABLE: 未知 action")
        io.github.easylsp.app.platform.tools.ToolArguments.validate(schema, args)
        if (action == "status") return status()
        if (action == "list_apps") return apps(args)
        if (action == "release") { release(conversationId); return JSONObject().put("released", true) }
        val backend = requireNotNull(service) { "PHONE_SERVICE_DISABLED: 请在系统无障碍设置开启 Easylsp 手机操作" }
        acquire(conversationId)
        return serial.withLock {
            acquire(conversationId)
            coroutineScope {
                active = currentCoroutineContext().job
                try {
                    withContext(Dispatchers.Main) {
                        currentCoroutineContext().ensureActive()
                        when (action) {
                            "observe" -> observe(backend, conversationId, supportsImages, args)
                            "launch" -> {
                                val intent = requireNotNull(graph.packageManager.getLaunchIntentForPackage(args.getString("packageName"))) { "TARGET_NOT_FOUND: 应用没有可见启动入口" }
                                backend.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); completed(action)
                            }
                            "open_uri" -> {
                                val text = args.getString("uri"); val uri = java.net.URI(text); require(uri.isAbsolute) { "uri 必须为完整 URI" }
                                backend.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(text)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); completed(action)
                            }
                            "key" -> {
                                val keyName = args.getString("key")
                                val global = keys[keyName]
                                if (global != null) { check(backend.performGlobalAction(global)) { "ACTION_FAILED: 系统未执行此按键" }; completed(action) }
                                else {
                                    val code = requireNotNull(mapOf("enter" to 66, "volume_up" to 24, "volume_down" to 25, "power" to 26)[keyName]) { "CAPABILITY_UNAVAILABLE: 不支持此键" }
                                    require(graph.root.state.value.granted) { "CAPABILITY_UNAVAILABLE: 此系统键需要已授予 root" }
                                    val output = StringBuilder()
                                    val result = withContext(Dispatchers.IO) { graph.executions.runner.execute(UUID.randomUUID().toString(), listOf(graph.root.su, "-c", "input keyevent $code"), graph.filesDir, closeInput = true) { output.append(it) } }
                                    check(result.exitCode == 0) { "ACTION_FAILED: $output" }; completed(action).put("backend", "root")
                                }
                            }
                            "wait" -> waitFor(backend, args)
                            "set_text" -> {
                                val snapshot = snapshot(backend, conversationId, args)
                                val node = node(snapshot, args.getString("nodeId")); require(node.isEditable) { "TARGET_NOT_FOUND: 节点不可编辑" }
                                check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, args.getString("text")) })) { "ACTION_FAILED: 节点拒绝输入" }
                                completed(action)
                            }
                            "tap", "long_press", "swipe", "gesture" -> {
                                val snapshot = snapshot(backend, conversationId, args)
                                val builder = GestureDescription.Builder().setDisplayId(snapshot.displayId)
                                if (action == "tap" || action == "long_press") {
                                    val target = args.getJSONObject("target")
                                    require(target.has("nodeId") != (target.has("x") && target.has("y"))) { "target 必须为 nodeId 或 x/y" }
                                    val targetNode = if (target.has("nodeId")) node(snapshot, target.getString("nodeId")) else null
                                    if (action == "tap" && targetNode != null) {
                                        check(targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "ACTION_FAILED: 节点拒绝点击" }
                                        return@withContext completed(action)
                                    }
                                    val point = targetNode?.let { Rect().also(it::getBoundsInScreen).let { rect -> JSONObject().put("x", rect.centerX()).put("y", rect.centerY()) } } ?: target
                                    val path = Path(); val xy = point(snapshot, point); path.moveTo(xy.first, xy.second)
                                    val duration = if (action == "tap") 1L else args.optLong("durationMs", 600)
                                    require(duration in 1..GestureDescription.getMaxGestureDuration())
                                    builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                                } else if (action == "swipe") {
                                    val from = point(snapshot, args.getJSONObject("from")); val to = point(snapshot, args.getJSONObject("to"))
                                    val duration = args.optLong("durationMs", 400); require(duration in 1..GestureDescription.getMaxGestureDuration())
                                    builder.addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(from.first, from.second); lineTo(to.first, to.second) }, 0, duration))
                                } else {
                                    val strokes = args.getJSONArray("strokes"); require(strokes.length() in 1..GestureDescription.getMaxStrokeCount())
                                    for (i in 0 until strokes.length()) {
                                        val stroke = strokes.getJSONObject(i); val points = stroke.getJSONArray("points"); require(points.length() > 0)
                                        val path = Path()
                                        for (j in 0 until points.length()) { val xy = point(snapshot, points.getJSONObject(j)); if (j == 0) path.moveTo(xy.first, xy.second) else path.lineTo(xy.first, xy.second) }
                                        val start = stroke.optLong("startMs", 0); val duration = stroke.getLong("durationMs"); require(start >= 0 && duration > 0 && start + duration <= GestureDescription.getMaxGestureDuration())
                                        builder.addStroke(GestureDescription.StrokeDescription(path, start, duration))
                                    }
                                }
                                gesture(backend, builder.build()); completed(action)
                            }
                            else -> error("CAPABILITY_UNAVAILABLE: 未知 action $action")
                        }
                    }
                } finally { active = null }
            }
        }
    }
    private fun apps(args: JSONObject): JSONObject {
        val query = args.optString("query"); val cursor = args.optInt("cursor", 0); val limit = args.optInt("limit", 30); require(cursor >= 0 && limit > 0)
        val pm = graph.packageManager
        val rows = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { JSONObject().put("name", it.loadLabel(pm).toString()).put("packageName", it.activityInfo.packageName).put("activity", it.activityInfo.name) }
            .filter { it.toString().contains(query, true) }.sortedBy { it.getString("name") }
        return JSONObject().put("apps", JSONArray(rows.drop(cursor).take(limit))).put("nextCursor", if (cursor + limit < rows.size) cursor + limit else JSONObject.NULL).put("scope", "当前 Android 用户可见的启动入口")
    }
    private fun collect(backend: PhoneAccessibilityService, displayId: Int = 0): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo) { if (node.isVisibleToUser) add(node); for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
        backend.windowsOnAllDisplays.get(displayId).orEmpty().forEach { it.root?.let(::visit) }
    }
    private fun activeWindow(backend: PhoneAccessibilityService, displayId: Int) = backend.windowsOnAllDisplays.get(displayId).orEmpty().firstOrNull { it.isActive }?.id ?: -1
    private fun fingerprint(node: AccessibilityNodeInfo): String = listOf(node.windowId, node.viewIdResourceName, node.className, node.text, node.contentDescription, Rect().also(node::getBoundsInScreen)).joinToString("|")
    private fun display(id: Int) = requireNotNull(graph.getSystemService(DisplayManager::class.java).getDisplay(id)) { "CAPABILITY_UNAVAILABLE: 显示屏不存在" }
    private suspend fun observe(backend: PhoneAccessibilityService, owner: String, supportsImages: Boolean, args: JSONObject): JSONObject {
        val mode = args.optString("mode", "nodes"); require(mode in setOf("nodes", "screen", "both"))
        require(mode != "screen" || listOf("query", "cursor", "limit").none(args::has)) { "screen 不接受节点分页参数" }
        require(mode == "nodes" || supportsImages) { "MODEL_INPUT_UNSUPPORTED: 当前模型不支持截图，请使用 nodes" }
        val displayId = args.optInt("displayId", 0); val screen = display(displayId)
        val metrics = android.util.DisplayMetrics(); screen.getRealMetrics(metrics)
        val cursor = args.optInt("cursor", 0); val limit = args.optInt("limit", 30); require(cursor >= 0 && limit > 0)
        val query = args.optString("query")
        val snapshot = if (cursor > 0) requireNotNull(observation) { "请先获取第一页" }.also {
            require(it.owner == owner && it.displayId == displayId && it.query == query && it.rotation == screen.rotation && it.version == contentVersion) { "STALE_OBSERVATION: 分页期间页面或查询改变，请重新观察" }
        } else collect(backend, displayId).let { nodes -> Observation("obs-${UUID.randomUUID()}", owner, displayId, screen.rotation, metrics.widthPixels, metrics.heightPixels,
                activeWindow(backend, displayId), contentVersion, nodes, nodes.map(::fingerprint), query, System.currentTimeMillis()).also { observation = it } }
        val nodes = snapshot.nodes.mapIndexed { i, node ->
            val rect = Rect().also(node::getBoundsInScreen)
            JSONObject().put("id", "n$i").put("text", node.text?.toString().orEmpty()).put("description", node.contentDescription?.toString().orEmpty())
                .put("resourceId", node.viewIdResourceName).put("packageName", node.packageName?.toString()).put("windowId", node.windowId)
                .put("clickable", node.isClickable).put("editable", node.isEditable).put("scrollable", node.isScrollable).put("enabled", node.isEnabled)
                .put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
        }.filter { it.toString().contains(query, true) }
        val result = JSONObject().put("snapshotId", snapshot.id).put("capturedAt", snapshot.createdAt).put("displayId", displayId).put("rotation", snapshot.rotation)
            .put("packageName", backend.rootInActiveWindow?.packageName?.toString()).put("coordinateSpace", JSONObject().put("width", snapshot.width).put("height", snapshot.height))
        if (mode != "screen") result.put("nodes", JSONArray(nodes.drop(cursor).take(limit))).put("nextCursor", if (cursor + limit < nodes.size) cursor + limit else JSONObject.NULL)
        if (mode == "nodes" || cursor > 0) return result
        val bitmap = screenshot(backend, displayId)
        require(bitmap.width == snapshot.width && bitmap.height == snapshot.height && screen.rotation == snapshot.rotation) { "STALE_OBSERVATION: 截图期间显示方向改变，请重新观察" }
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }; bitmap.recycle()
        val imageId = UUID.randomUUID().toString(); val file = File(graph.filesDir, "conversation-media/$owner/$imageId.png"); file.parentFile!!.mkdirs(); file.writeBytes(bytes)
        result.put("image", JSONObject().put("imageId", imageId).put("mimeType", "image/png").put("width", snapshot.width).put("height", snapshot.height).put("path", file.path))
        return ToolResults.image(result, "image/png", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }
    private fun snapshot(backend: PhoneAccessibilityService, owner: String, args: JSONObject): Observation {
        val value = observation
        require(value != null && value.id == args.getString("snapshotId") && value.owner == owner && value.version == contentVersion && display(value.displayId).rotation == value.rotation && activeWindow(backend, value.displayId) == value.window) { "STALE_OBSERVATION: 窗口或方向已改变，请重新观察" }
        return value
    }
    private fun node(snapshot: Observation, id: String): AccessibilityNodeInfo {
        val index = id.removePrefix("n").toIntOrNull(); val node = index?.let { snapshot.nodes.getOrNull(it) }
        require(node != null && node.refresh() && node.isVisibleToUser && fingerprint(node) == snapshot.fingerprints[index!!]) { "STALE_OBSERVATION: 节点已失效，请重新观察" }; return node
    }
    private fun point(snapshot: Observation, value: JSONObject): Pair<Float, Float> {
        val x = value.getDouble("x").toFloat(); val y = value.getDouble("y").toFloat()
        require(x >= 0 && y >= 0 && x < snapshot.width && y < snapshot.height) { "坐标超出观察画面" }; return x to y
    }
    private suspend fun waitFor(backend: PhoneAccessibilityService, args: JSONObject): JSONObject {
        val condition = args.getString("condition"); require(condition in setOf("idle", "window_changed", "text_visible"))
        val timeout = args.optLong("timeoutMs", 3000); require(timeout in 1..10000)
        val reference = if (condition == "window_changed") requireNotNull(observation?.takeIf { it.id == args.getString("snapshotId") }) { "STALE_OBSERVATION" } else null
        val text = if (condition == "text_visible") args.getString("text") else ""
        val start = SystemClock.uptimeMillis()
        do {
            val matched = when (condition) {
                "idle" -> SystemClock.uptimeMillis() - changedAt >= 500
                "window_changed" -> contentVersion != reference!!.version || backend.rootInActiveWindow?.windowId != reference.window
                else -> collect(backend).any { it.text?.contains(text) == true || it.contentDescription?.contains(text) == true }
            }
            if (matched) return JSONObject().put("status", "matched").put("condition", condition).put("packageName", backend.rootInActiveWindow?.packageName?.toString())
            if (SystemClock.uptimeMillis() - start >= timeout) break
            delay(minOf(100, timeout - (SystemClock.uptimeMillis() - start)).coerceAtLeast(1))
        } while (true)
        return JSONObject().put("status", "timed_out").put("condition", condition)
    }
    private suspend fun gesture(backend: PhoneAccessibilityService, gesture: GestureDescription) = suspendCancellableCoroutine<Unit> { continuation ->
        val accepted = backend.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(Unit) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("CANCELLED: 手势已取消")) }
        }, null)
        if (!accepted && continuation.isActive) continuation.resumeWithException(IllegalStateException("ACTION_FAILED: 系统拒绝手势"))
    }
    private suspend fun screenshot(backend: PhoneAccessibilityService, displayId: Int): Bitmap = suspendCancellableCoroutine { continuation ->
        backend.takeScreenshot(displayId, graph.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                val copy = hardware?.copy(Bitmap.Config.ARGB_8888, false); hardware?.recycle(); buffer.close()
                if (continuation.isActive) { if (copy != null) continuation.resume(copy) else continuation.resumeWithException(IllegalStateException("SCREENSHOT_UNAVAILABLE: 无法读取截图")) } else copy?.recycle()
            }
            override fun onFailure(errorCode: Int) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("SCREENSHOT_UNAVAILABLE: Android error $errorCode")) }
        })
    }
    private fun completed(action: String) = JSONObject().put("action", action).put("status", "completed").put("actionId", "act-${UUID.randomUUID()}").put("backend", "accessibility").put("observationRequired", true)
}
