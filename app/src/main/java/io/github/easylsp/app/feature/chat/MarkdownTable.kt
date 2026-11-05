package io.github.easylsp.app.feature.chat

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.commonmark.ext.gfm.tables.*
import org.commonmark.node.*
import org.commonmark.parser.Parser

internal data class MarkdownCell(val text: String, val header: Boolean, val alignment: TableCell.Alignment?)
private val tableParser = Parser.builder().extensions(listOf(TablesExtension.create())).build()
internal fun markdownTable(text: String): List<List<MarkdownCell>> {
    val rows = mutableListOf<List<MarkdownCell>>()
    fun plain(node: Node): String = when (node) {
        is Text -> node.literal
        is Code -> node.literal
        is SoftLineBreak, is HardLineBreak -> "\n"
        is HtmlInline -> if (node.literal.matches(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE))) "\n" else node.literal
        else -> buildString { var child = node.firstChild; while (child != null) { append(plain(child)); child = child.next } }
    }
    fun visit(node: Node) {
        if (node is TableRow) {
            val cells = mutableListOf<MarkdownCell>(); var cell = node.firstChild
            while (cell != null) { if (cell is TableCell) cells += MarkdownCell(plain(cell), cell.isHeader, cell.alignment); cell = cell.next }
            rows += cells
        } else {
            var child = node.firstChild
            while (child != null) { visit(child); child = child.next }
        }
    }
    visit(tableParser.parse(text))
    return rows
}

@Composable internal fun MarkdownTable(content: String) {
    val rows = remember(content) { markdownTable(content) }
    if (rows.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.bodyMedium
    val border = MaterialTheme.colorScheme.outlineVariant
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widths = remember(rows.first(), maxWidth, density.density, density.fontScale, style) {
            val columns = rows.first().size
            val base = (maxWidth / columns).coerceIn(120.dp, 240.dp)
            val natural = rows.first().map { cell ->
                val px = measurer.measure(AnnotatedString(cell.text), style, softWrap = false).size.width
                maxOf(base, with(density) { px.toDp() } + 24.dp).coerceAtMost(240.dp)
            }
            val total = natural.fold(0.dp) { a, b -> a + b }
            if (total < maxWidth) natural.map { it + (maxWidth - total) / columns } else natural
        }
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            rows.forEach { row ->
                Row(Modifier.height(IntrinsicSize.Min)) {
                    widths.forEachIndexed { index, width ->
                        val cell = row.getOrNull(index)
                        Text(cell?.text.orEmpty(), style = style, fontWeight = if (cell?.header == true) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = when (cell?.alignment) { TableCell.Alignment.CENTER -> androidx.compose.ui.text.style.TextAlign.Center; TableCell.Alignment.RIGHT -> androidx.compose.ui.text.style.TextAlign.End; else -> androidx.compose.ui.text.style.TextAlign.Start },
                            modifier = Modifier.width(width).fillMaxHeight().border(.5.dp, border).padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
        }
    }
}
