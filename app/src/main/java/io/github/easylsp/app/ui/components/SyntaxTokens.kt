package io.github.easylsp.app.ui.components

object SyntaxTokens {
    fun pattern(extension: String): Regex = when (extension) {
        "c", "h" -> Regex("//.*|#.*|\"[^\"]*\"|\\b(?:void|static|const|struct|typedef|enum|int|long|char|unsigned|signed|sizeof|return|if|else|for|while|uint32_t|uint64_t|uintptr_t|NULL|[0-9]+)\\b")
        "json" -> Regex("\"(?:[^\"\\\\]|\\\\.)*\"|\\b(?:true|false|null|[0-9]+(?:\\.[0-9]+)?)\\b")
        "xml" -> Regex("<!--.*?(?:-->|$)|</?[\\w:.-]+|\"[^\"]*\"|'[^']*'|\\b[\\w:.-]+(?==)")
        "smali" -> Regex("#.*|\"(?:[^\"\\\\]|\\\\.)*\"|\\.[a-z-]+|\\b(?:invoke-[a-z/-]+|move-[a-z/-]+|return[a-z-]*|const[a-z/-]*|[vp][0-9]+)\\b")
        else -> Regex("//.*|\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|\\b(?:void|public|private|protected|static|final|new|int|long|float|double|boolean|char|byte|short|String|return|if|else|throw|throws|class|import|try|catch|for|while|true|false|null|[0-9]+)\\b")
    }
}
