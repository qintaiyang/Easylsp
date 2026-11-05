package io.github.easylsp.app.feature.chat

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import io.github.easylsp.app.ui.components.SyntaxTokens
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon

@Composable fun MarkdownMessage(text: String, markwon: Markwon, openCode: (String, String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    val textPixels = with(LocalDensity.current) { 15.sp.toPx() }
    val cache = remember { MarkdownBlockCache() }
    val chunks = remember(text) { cache.update(text) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chunks.forEach { block -> key(block.start) {
            val content = block.content; val language = block.language
            if (block.kind == "code") Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small) {
                var visibleLines by rememberSaveable(block.start) { mutableIntStateOf(18) }
                Column(Modifier.padding(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(language.ifEmpty { "代码" }, style = MaterialTheme.typography.labelMedium)
                        Row { TextButton(onClick = { clipboard.setText(AnnotatedString(content)) }, contentPadding = PaddingValues(4.dp)) { Text("复制") }; TextButton(onClick = { openCode(language, content) }, contentPadding = PaddingValues(4.dp)) { Text("全屏查看") } }
                    }
                    val tokenColor = MaterialTheme.colorScheme.primary
                    val preview = remember(content, language, tokenColor, visibleLines) {
                        val code = content.lineSequence().take(visibleLines).joinToString("\n")
                        buildAnnotatedString {
                            var end = 0
                            SyntaxTokens.pattern(language).findAll(code).forEach { match ->
                                append(code.substring(end, match.range.first)); withStyle(SpanStyle(color = tokenColor)) { append(match.value) }; end = match.range.last + 1
                            }
                            append(code.substring(end))
                        }
                    }
                    Text(preview, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.horizontalScroll(rememberScrollState()))
                    if (content.lines().size > 18) TextButton(onClick = { visibleLines = if (visibleLines >= content.lines().size) 18 else visibleLines + 60 }) { Text(if (visibleLines >= content.lines().size) "收起代码" else "显示更多（${content.lines().size} 行）") }
                }
            } else if (block.kind == "table") MarkdownTable(content)
            else AndroidView(factory = { context -> TextView(context).apply { textSize = 15f; setTextIsSelectable(true); movementMethod = LinkMovementMethod.getInstance() } }, onReset = { view -> view.tag = null; view.text = "" }, update = { view ->
                val renderKey = listOf(content, color, primary, textPixels)
                if (view.tag != renderKey) {
                    view.setTextColor(color); view.setLinkTextColor(primary)
                    view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textPixels)
                    markwon.setMarkdown(view, content); view.tag = renderKey
                }
            }, modifier = Modifier.fillMaxWidth())
        } }
    }
}
