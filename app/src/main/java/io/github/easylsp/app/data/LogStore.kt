package io.github.easylsp.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File

class LogStore(context: Context) {
    private val file = File(context.filesDir, "runtime.jsonl")
    val lines = MutableStateFlow<List<String>>(emptyList())
    init { if (file.exists()) lines.value = file.bufferedReader().useLines { lines ->
        val tail = ArrayDeque<String>(); lines.forEach { line -> if (tail.size == 500) tail.removeFirst(); tail.addLast(line) }; tail.toList()
    } }
    @Synchronized fun append(event: JSONObject) {
        val line = event.toString()
        file.appendText(line + "\n")
        lines.value = (lines.value + line).takeLast(500)
    }
    @Synchronized fun clear(filter: ((JSONObject) -> Boolean)? = null) {
        if (filter == null) { file.writeText(""); lines.value = emptyList(); return }
        val remaining = if (file.exists()) file.readLines().filterNot { filter(JSONObject(it)) } else emptyList()
        file.writeText(remaining.joinToString("\n", postfix = if (remaining.isEmpty()) "" else "\n"))
        lines.value = remaining.takeLast(500)
    }
    @Synchronized fun text(filter: (JSONObject) -> Boolean): String = if (file.exists()) file.bufferedReader().useLines { rows -> rows.filter { filter(JSONObject(it)) }.joinToString("\n") } else ""
    @Synchronized fun export(output: java.io.OutputStream, filter: ((JSONObject) -> Boolean)? = null) { output.use { if (filter != null) it.write(text(filter).toByteArray()) else if (file.exists()) file.inputStream().use { input -> input.copyTo(it) } } }
}
