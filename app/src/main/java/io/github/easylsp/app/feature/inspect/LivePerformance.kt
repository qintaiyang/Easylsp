package io.github.easylsp.app.feature.inspect

import android.os.Bundle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import io.github.easylsp.app.MainViewModel
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable fun LivePerformance(vm: MainViewModel, sessionId: String) {
    var sampling by remember(sessionId) { mutableStateOf(false) }
    var interval by remember { mutableIntStateOf(1000) }
    var threshold by remember { mutableFloatStateOf(50f) }
    var rows by remember(sessionId) { mutableStateOf(emptyList<JSONObject>()) }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(sampling, interval, sessionId) {
        if (sampling) {
            try {
                vm.graph.bridge.commandSession(sessionId, "performanceStart")
                while (isActive) {
                    delay(interval.toLong())
                    rows = (rows + JSONObject(vm.graph.bridge.commandSession(sessionId, "performanceRead"))).takeLast(120)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { status = e.message ?: "采样失败"; sampling = false }
            finally { withContext(NonCancellable) { if (vm.graph.bridge.sessions.value.containsKey(sessionId)) vm.graph.bridge.commandSession(sessionId, "performanceStop") } }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Button(onClick = { sampling = !sampling }) { Text(if (sampling) "停止持续采样" else "持续采样") }
            TextButton(enabled = sampling, onClick = { vm.action { vm.graph.bridge.commandSession(sessionId, "performanceOverlay", arguments = Bundle().apply { putBoolean("enabled", true) }); "目标已显示可拖动性能面板" } }) { Text("目标内面板") }
            TextButton(onClick = { interval = if (interval == 1000) 2000 else 1000 }) { Text("${interval}ms") }
        }
        Text("FPS 阈值 ${threshold.toInt()} · 最多 120 个读数 · 累计采样口径", style = MaterialTheme.typography.labelSmall)
        Slider(threshold, { threshold = it }, valueRange = 15f..120f)
        val color = MaterialTheme.colorScheme.primary; val warning = MaterialTheme.colorScheme.error
        Canvas(Modifier.fillMaxWidth().height(100.dp)) {
            val scale = 144f
            drawLine(warning.copy(alpha = .6f), Offset(0f, size.height * (1 - threshold / scale)), Offset(size.width, size.height * (1 - threshold / scale)))
            if (rows.size > 1) {
                val path = Path(); rows.forEachIndexed { i, row -> val x = i * size.width / (rows.size - 1); val y = size.height * (1 - row.optDouble("fps").toFloat().coerceIn(0f, scale) / scale); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
            }
        }
        rows.lastOrNull()?.let { row -> Text("FPS %.1f · 1%% low %.1f\nCPU %.1f%% · PSS %.1f MiB\n电量 %s · 电池温度 %s".format(row.optDouble("fps"), row.optDouble("fpsOnePercentLow"), row.optDouble("cpuPercentOneCore"), row.optDouble("totalPssKb") / 1024, if (row.has("batteryPercent")) "${row.getDouble("batteryPercent")}%" else "未提供", if (row.has("batteryTemperatureC")) "${row.getDouble("batteryTemperatureC")}℃" else "未提供"), style = MaterialTheme.typography.bodySmall) }
        if (status.isNotEmpty()) Text(status)
    }
}
