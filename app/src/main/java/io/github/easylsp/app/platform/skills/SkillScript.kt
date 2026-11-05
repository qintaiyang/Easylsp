package io.github.easylsp.app.platform.skills

import bsh.Interpreter
import org.json.JSONObject
import java.io.OutputStream
import java.io.PrintStream

object SkillScript {
    fun run(source: String, arguments: JSONObject, workspace: String, output: (String) -> Unit): JSONObject {
        val stream = PrintStream(object : OutputStream() {
            override fun write(value: Int) { output(value.toChar().toString()) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { output(String(bytes, offset, length, Charsets.UTF_8)) }
        }, true, "UTF-8")
        val interpreter = Interpreter().apply {
            setClassLoader(javaClass.classLoader); setOut(stream); setErr(stream)
            set("arguments", arguments); set("workspace", workspace)
        }
        return try { JSONObject().put("result", interpreter.eval(source) ?: JSONObject.NULL) }
        finally { interpreter.nameSpace.clear(); stream.close() }
    }
}
