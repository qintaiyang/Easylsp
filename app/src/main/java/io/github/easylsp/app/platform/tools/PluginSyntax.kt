package io.github.easylsp.app.platform.tools

import bsh.Parser
import bsh.ParseException
import bsh.TokenMgrError
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader

/** Parser only: no interpreter, class loading, onLoad or target connection. */
object PluginSyntax {
    fun validateFiles(files: Map<String, String>): JSONObject {
        val program = try { io.github.easylsp.runtime.PluginProgram(files.filterKeys { !it.endsWith('/') }) }
        catch (error: Exception) { return JSONObject().put("valid", false).put("executed", false).put("path", "plugin.json").put("message", error.message) }
        for (path in listOf("main.java") + program.components.map { it.path }) {
            val result = validate(program.text(path))
            if (!result.getBoolean("valid")) return result.put("path", path)
        }
        return JSONObject().put("valid", true).put("executed", false).put("validation", "STRUCTURE_AND_SYNTAX_ONLY")
            .put("components", JSONArray(program.components.map { it.id })).put("provides", JSONObject(program.provides)).put("requires", JSONObject(program.requires))
    }
    fun validate(source: String): JSONObject {
        val parser = Parser(StringReader(source))
        try {
            while (!parser.Line()) parser.popNode()
        } catch (error: ParseException) {
            val token = error.currentToken?.next
            return failure(source, error.message.orEmpty(), token?.beginLine ?: error.errorLineNumber, token?.beginColumn)
        } catch (error: TokenMgrError) {
            val location = Regex("line (\\d+), column (\\d+)").find(error.message.orEmpty())
            return failure(source, error.message.orEmpty(), location?.groupValues?.get(1)?.toInt(), location?.groupValues?.get(2)?.toInt())
        }
        return JSONObject().put("valid", true).put("executed", false).put("validation", "SYNTAX_ONLY")
    }
    private fun failure(source: String, message: String, line: Int?, column: Int?): JSONObject {
        val lines = source.lines()
        val nearby = if (line != null && line > 0) (maxOf(0, line - 2) until minOf(lines.size, line + 1)).map {
            JSONObject().put("line", it + 1).put("text", lines[it])
        } else emptyList()
        return JSONObject().put("valid", false).put("executed", false).put("validation", "SYNTAX_ONLY")
            .put("line", line ?: JSONObject.NULL).put("column", column ?: JSONObject.NULL)
            .put("message", message).put("nearbySource", JSONArray(nearby))
    }
}
