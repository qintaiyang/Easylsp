package io.github.easylsp.app.feature.chat

/** Positions, rather than content or language names, identify growing blocks. */
data class MarkdownBlock(val start: Int, val kind: String, val content: String, val language: String = "")

fun markdownBlocks(text: String): List<MarkdownBlock> {
    val lines = text.split('\n')
    val offsets = IntArray(lines.size)
    for (i in 1 until lines.size) offsets[i] = offsets[i - 1] + lines[i - 1].length + 1
    val blocks = mutableListOf<MarkdownBlock>()
    val fence = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
    val separator = Regex("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$")
    fun slice(from: Int, until: Int) = lines.subList(from, until).joinToString("\n")
    var i = 0
    while (i < lines.size) {
        if (lines[i].isBlank()) { i++; continue }
        val begin = i
        val opening = fence.matchEntire(lines[i])
        if (opening != null) {
            val marker = opening.groupValues[1]
            val language = opening.groupValues[2].trim()
            i++
            val body = i
            while (i < lines.size && !Regex("^ {0,3}${Regex.escape(marker.first().toString())}{${marker.length},}\\s*$").matches(lines[i])) i++
            // Keep the original line endings, including the newline before a closing fence.
            val contentStart = if (body < offsets.size) offsets[body] else text.length
            val contentEnd = if (i < offsets.size) offsets[i] else text.length
            blocks += MarkdownBlock(offsets[begin], "code", text.substring(contentStart, contentEnd), language)
            if (i < lines.size) i++
        } else if (i + 1 < lines.size && lines[i].contains('|') && separator.matches(lines[i + 1])) {
            i += 2
            while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) i++
            blocks += MarkdownBlock(offsets[begin], "table", slice(begin, i))
        } else {
            i++
            while (i < lines.size && lines[i].isNotBlank() && fence.matchEntire(lines[i]) == null &&
                !(i + 1 < lines.size && lines[i].contains('|') && separator.matches(lines[i + 1]))) i++
            blocks += MarkdownBlock(offsets[begin], "text", slice(begin, i))
        }
    }
    return blocks
}

/** Reuse the immutable prefix when only the final Markdown block is growing. */
class MarkdownBlockCache {
    private var previousText = ""
    private var previous = emptyList<MarkdownBlock>()
    fun update(text: String): List<MarkdownBlock> {
        val keep = if (text.startsWith(previousText)) previous.dropLast(1) else emptyList()
        val start = keep.lastOrNull()?.let { previous[keep.size].start } ?: 0
        val tail = markdownBlocks(text.substring(start)).map { it.copy(start = it.start + start) }
        previous = keep + tail
        previousText = text
        return previous
    }
}
