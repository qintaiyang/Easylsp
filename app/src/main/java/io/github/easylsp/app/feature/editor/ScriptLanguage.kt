package io.github.easylsp.app.feature.editor

import android.os.Bundle
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/** Lexical highlighting and snippets, without Java type resolution. */
class ScriptLanguage(private val extension: String) : EmptyLanguage() {
    private val manager = object : SimpleAnalyzeManager<Unit>() {
        override fun analyze(text: StringBuilder, delegate: Delegate<Unit>): Styles {
            val spans = MappedSpans.Builder()
            text.lineSequence().forEachIndexed { line, value ->
                if (delegate.isCancelled) return Styles(spans.build())
                spans.addIfNeeded(line, 0, EditorColorScheme.TEXT_NORMAL.toLong())
                tokens.findAll(value).forEach { token ->
                    val color = when {
                        token.value.startsWith("//") || token.value.startsWith('#') || token.value.startsWith("<!--") -> EditorColorScheme.COMMENT
                        token.value.startsWith('"') || token.value.startsWith('\'') -> EditorColorScheme.LITERAL
                        token.value.first().isDigit() -> EditorColorScheme.LITERAL
                        else -> EditorColorScheme.KEYWORD
                    }
                    spans.addIfNeeded(line, token.range.first, color.toLong())
                    spans.addIfNeeded(line, token.range.last + 1, EditorColorScheme.TEXT_NORMAL.toLong())
                }
            }
            return Styles(spans.build())
        }
    }
    private val tokens = io.github.easylsp.app.ui.components.SyntaxTokens.pattern(extension)
    override fun getAnalyzeManager(): AnalyzeManager = manager
    override fun getIndentAdvance(content: ContentReference, line: Int, column: Int): Int = if (content.getLine(line).take(column).trimEnd().endsWith('{')) 4 else 0
    override fun requireAutoComplete(content: ContentReference, position: CharPosition, publisher: CompletionPublisher, extraArguments: Bundle) {
        val prefix = content.getLine(position.line).take(position.column).takeLastWhile { it.isLetterOrDigit() || it in "._" }
        if (prefix.isEmpty()) return
        val snippets = if (extension in listOf("c", "h")) listOf("static", "const", "void", "return", "int", "uint32_t", "uintptr_t", "host->log(ctx->instance, \"message\");", "host->hit(ctx->instance, hook_id);", "SH_REQUIRE_BEFORE_CONSTRUCTORS", "EASYLSP_PLUGIN_ENTRY") else listOf("void", "public", "private", "return", "new", "String", "Integer", "Boolean", "plugin.log(\"message\");", "hook.before(", "hook.after(", "hook.replace(", "param.args", "param.getResult()", "param.setResult(", "void onLoad() {\n    \n}", "void onUnload() {\n    \n}")
        val words = Regex("[A-Za-z_$][A-Za-z0-9_$]*").findAll(content.toString()).map { it.value }.distinct().take(300)
        (snippets + words).distinct().filter { it.startsWith(prefix) && it != prefix }.take(30).forEach { publisher.addItem(SimpleCompletionItem(it, "词法 / 平台片段", prefix.length, it)) }
    }
    override fun destroy() { manager.destroy() }
}
