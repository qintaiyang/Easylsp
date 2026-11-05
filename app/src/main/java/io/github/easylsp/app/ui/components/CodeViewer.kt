package io.github.easylsp.app.ui.components

import io.github.easylsp.app.ui.icons.AppIcons
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class ViewerStore(private val directory: File) {
    fun put(text: String): String {
        directory.mkdirs()
        val id = UUID.randomUUID().toString()
        File(directory, id).writeText(text)
        return id
    }
    fun read(id: String): String = File(directory, id).readText()
}

@Composable fun CodeViewer(text: String, modifier: Modifier = Modifier, language: String = "text", firstLine: Int = 1) {
    var query by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable(text) { mutableIntStateOf(0) }
    val clipboard = LocalClipboardManager.current
    val colors = MaterialTheme.colorScheme
    val pattern = remember(language) { SyntaxTokens.pattern(language) }
    val lines = remember(text) { text.lines() }
    val matches = remember(lines, query) { lines.withIndex().filter { query.isEmpty() || it.value.contains(query, true) } }
    val count = ((matches.size + 149) / 150).coerceAtLeast(1)
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it; page = 0 }, label = { Text("查找内容") }, leadingIcon = { Icon(AppIcons.Search, null) }, singleLine = true, modifier = Modifier.weight(1f))
            IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Icon(AppIcons.ContentCopy, "复制全部") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${language.uppercase()} · ${lines.size} 行${if (query.isNotEmpty()) " · ${matches.size} 行匹配" else ""}", style = MaterialTheme.typography.labelMedium)
            Text("${page + 1} / $count", style = MaterialTheme.typography.labelMedium)
        }
        Surface(Modifier.weight(1f).fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
            SelectionContainer(Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp)) {
                Column { matches.drop(page * 150).take(150).forEach { line ->
                    Row { Text("${line.index + firstLine}".padStart(4) + "  ", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall); Text(buildAnnotatedString {
                        append(line.value.ifEmpty { " " })
                        if (language != "text") pattern.findAll(line.value).forEach { token ->
                            addStyle(SpanStyle(color = when {
                                token.value.startsWith("//") || token.value.startsWith('#') || token.value.startsWith("<!--") -> colors.onSurfaceVariant
                                token.value.startsWith('"') || token.value.startsWith('\'') || token.value.first().isDigit() -> colors.tertiary
                                else -> colors.primary
                            }), token.range.first, token.range.last + 1)
                        }
                    }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                } }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = page > 0, onClick = { page-- }) { Text("上一页") }
            TextButton(enabled = page + 1 < count, onClick = { page++ }) { Text("下一页") }
        }
    }
}

@Composable fun StoredViewer(store: ViewerStore, id: String, language: String) {
    var text by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id) { text = withContext(Dispatchers.IO) { store.read(id) } }
    val value = text
    if (value == null) LoadingState() else CodeViewer(value, Modifier.padding(16.dp), language)
}
