package io.github.easylsp.app.platform.network
import okio.BufferedSource
/** SSE lines, including multiline data and comments. false stops reading. */
object SseReader {
    fun read(source: BufferedSource, accept: (String, String) -> Boolean) {
        var event = "message"
        val data = StringBuilder()
        fun dispatch(): Boolean {
            if (data.isEmpty()) { event = "message"; return true }
            val keepReading = accept(event, data.toString().removeSuffix("\n"))
            data.setLength(0); event = "message"
            return keepReading
        }
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (line.isEmpty()) { if (!dispatch()) return; continue }
            if (line.startsWith(":")) continue
            val field = line.substringBefore(':'); val value = line.substringAfter(':', "").removePrefix(" ")
            when (field) { "event" -> event = value; "data" -> data.append(value).append('\n') }
        }
        dispatch()
    }
}
