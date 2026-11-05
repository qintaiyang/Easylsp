package io.github.easylsp.app.feature.editor

data class MethodSignature(val owner: String, val name: String, val parameters: List<String>, val returns: String)
object HookTemplate {
    fun parse(input: String): MethodSignature {
        val match = Regex("^(L[^;]+;)->([A-Za-z_$][A-Za-z0-9_$]*|<init>)\\((.*)\\)(.+)$").matchEntire(input.trim()) ?: error("请输入完整 DEX 方法签名，例如 Ldemo/Calculator;->add(II)I")
        fun types(text: String, allowVoid: Boolean): List<String> {
            val result = mutableListOf<String>(); var i = 0
            while (i < text.length) {
                var arrays = 0
                while (i < text.length && text[i] == '[') { arrays++; i++ }
                require(i < text.length) { "数组类型缺少元素" }
                val type = if (text[i] == 'L') {
                    val end = text.indexOf(';', i); require(end > i + 1) { "对象类型缺少分号" }
                    text.substring(i + 1, end).replace('/', '.').also { i = end + 1 }
                } else {
                    val token = text[i++]
                    require(token != 'V' || (allowVoid && arrays == 0 && text.length == 1)) { "void 只能作为返回类型" }
                    mapOf('V' to "void", 'Z' to "boolean", 'B' to "byte", 'S' to "short", 'C' to "char", 'I' to "int", 'J' to "long", 'F' to "float", 'D' to "double")[token] ?: error("未知类型：$token")
                }
                result += type + "[]".repeat(arrays)
            }
            return result
        }
        val returned = types(match.groupValues[4], true)
        require(returned.size == 1) { "返回类型必须唯一" }
        require(match.groupValues[2] != "<init>") { "当前 Hook API 仅支持方法，请选择普通方法" }
        return MethodSignature(match.groupValues[1].drop(1).dropLast(1).replace('/', '.'), match.groupValues[2], types(match.groupValues[3], false), returned.single())
    }
    fun generate(signature: MethodSignature, mode: String): String {
        require(mode in listOf("before", "after", "replace"))
        fun quote(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        val action = if (mode == "replace") {
            val result = when (signature.returns) { "void" -> "null"; "boolean" -> "Boolean.FALSE"; "char" -> "Character.valueOf('\\0')"; "byte" -> "Byte.valueOf((byte) 0)"; "short" -> "Short.valueOf((short) 0)"; "int" -> "Integer.valueOf(0)"; "long" -> "Long.valueOf(0L)"; "float" -> "Float.valueOf(0f)"; "double" -> "Double.valueOf(0d)"; else -> "null" }
            "param.setResult($result); // 按需求修改返回值"
        } else "plugin.log(\"${signature.name} · $mode\");"
        return """
            // ${signature.owner}#${signature.name}(${signature.parameters.joinToString()}) : ${signature.returns}
            hook.$mode(${quote(signature.owner)}, ${quote(signature.name)}, new String[] { ${signature.parameters.joinToString(", ", transform = ::quote)} }, new HookCallback() {
                public void call(HookParam param) {
                    $action
                }
            });
        """.trimIndent() + "\n"
    }
}
