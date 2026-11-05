package io.github.easylsp.app.feature.p3

import android.graphics.Typeface
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.util.Log
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.terminal.TerminalSession
import com.termux.view.*
import io.github.easylsp.app.MainViewModel
import io.github.easylsp.app.data.ProjectEntity

@Composable fun TerminalScreen(vm: MainViewModel, project: ProjectEntity?, linux: Boolean = false, workspaceId: String = requireNotNull(project).id) {
    val graph = vm.graph
    val sessions by graph.terminals.entries.collectAsStateWithLifecycle()
    var selected by rememberSaveable(workspaceId, linux) { mutableStateOf<String?>(null) }
    var ctrl by remember { mutableStateOf(false) }
    var textSize by rememberSaveable { mutableIntStateOf(14) }
    var view by remember { mutableStateOf<TerminalView?>(null) }
    val rows = sessions.filter { it.workspaceId == workspaceId }
    val entry = rows.firstOrNull { it.id == selected } ?: rows.lastOrNull()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            rows.forEach { row -> FilterChip(entry?.id == row.id, { selected = row.id }, label = { Text(row.title.take(18)) }) }
            TextButton(onClick = { vm.action { selected = if (linux) graph.linux.terminal(workspaceId).id else graph.terminals.create(workspaceId).id; "PTY 已创建" } }) { Text("新建${if (linux) " Linux" else "终端"}") }
        }
        Text("${entry?.id?.take(8) ?: ""} · PTY / xterm-256color · ${if (entry?.session?.isRunning == true) "运行中" else "已结束"}", style = MaterialTheme.typography.labelSmall)
        val currentEntry by rememberUpdatedState(entry)
        val context = LocalContext.current
        val client = remember { object : TerminalViewClient {
            override fun onScale(scale: Float): Float { textSize = (textSize * scale).toInt().coerceIn(9, 28); return 1f }
            override fun onSingleTapUp(e: MotionEvent) { view?.let { it.requestFocus(); (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(it, InputMethodManager.SHOW_IMPLICIT) } }
            override fun shouldBackButtonBeMappedToEscape() = false
            override fun shouldEnforceCharBasedInput() = false
            override fun shouldUseCtrlSpaceWorkaround() = false
            override fun isTerminalViewSelected() = true
            override fun copyModeChanged(copyMode: Boolean) {}
            override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
            override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
            override fun onLongPress(event: MotionEvent) = false
            override fun readControlKey(): Boolean { val result = ctrl; ctrl = false; return result }
            override fun readAltKey() = false
            override fun readShiftKey() = false
            override fun readFnKey() = false
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
            override fun onEmulatorSet() {}
            override fun logError(tag: String, message: String) { Log.e(tag, message) }
            override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
            override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
            override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
            override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
            override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, e.message, e) }
        } }
        AndroidView(factory = { TerminalView(it, null).apply { setTerminalViewClient(client); setTypeface(Typeface.MONOSPACE); view = this } }, update = { terminal -> terminal.setTextSize((textSize * context.resources.displayMetrics.scaledDensity).toInt()); entry?.let { terminal.attachSession(it.session) } }, modifier = Modifier.weight(1f).fillMaxWidth())
        DisposableEffect(Unit) {
            graph.terminals.changed = { session -> if (session === currentEntry?.session) view?.onScreenUpdated() }
            onDispose { graph.terminals.changed = null; view = null }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            FilterChip(ctrl, { ctrl = !ctrl }, label = { Text("Ctrl") })
            listOf("Tab" to "\t", "Esc" to "\u001b", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C", "中断" to "\u0003").forEach { (title, text) -> TextButton(onClick = { entry?.session?.write(text) }) { Text(title) } }
            TextButton(onClick = { entry?.let { graph.terminals.stop(workspaceId, it.id) } }) { Text("停止") }
        }
    }
}
