package io.github.easylsp.app

import io.github.easylsp.app.data.PluginArchive
import io.github.easylsp.app.feature.editor.HookTemplate
import io.github.easylsp.app.feature.chat.usageLabel
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UiWorkflowTest {
    @Test fun dexTemplatesDistinguishOverloadsAndArrays() {
        val first = HookTemplate.parse("Ldemo/Calculator;->add(II)I")
        val other = HookTemplate.parse("Ldemo/Calculator;->add([[I[Ljava/lang/String;)Ljava/lang/Object;")
        assertEquals(listOf("int", "int"), first.parameters)
        assertEquals(listOf("int[][]", "java.lang.String[]"), other.parameters)
        assertEquals("java.lang.Object", other.returns)
        val code = HookTemplate.generate(first, "replace")
        assertTrue(code.contains("hook.replace(\"demo.Calculator\", \"add\", new String[] { \"int\", \"int\" }"))
        assertTrue(code.contains("Integer.valueOf(0)"))
        assertFalse(HookTemplate.generate(other, "before").contains("setResult"))
    }
    @Test fun invalidDexSignaturesDoNotProduceTemplates() {
        listOf("add(II)", "Ldemo/C;->add(V)I", "Ldemo/C;->add([)I", "Ldemo/C;->add()II", "Ldemo/C;->add()Lbroken", "Ldemo/C;-><init>()V").forEach { bad ->
            try { HookTemplate.parse(bad); fail(bad) } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
        }
    }
    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip -> files.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() } } }.toByteArray()
    private val main = "main.java" to "void onLoad() {}".toByteArray()
    private val info = "info.prop" to "name=Example\napiVersion=1\nentry=main.java".toByteArray()
    @Test fun archiveAcceptsOldAndTextBundleFormats() {
        assertEquals(setOf("main.java", "info.prop"), PluginArchive.read(ByteArrayInputStream(zip(main, info))).keys)
        val files = PluginArchive.read(ByteArrayInputStream(zip(main, info, "notes/" to byteArrayOf(), "notes/说明.json" to "{\"title\":\"中文\"}".toByteArray())))
        assertEquals("{\"title\":\"中文\"}", files.getValue("notes/说明.json").toString(Charsets.UTF_8))
    }
    @Test fun archiveRejectsTraversalBinaryAndPathConflicts() {
        listOf("../out.txt" to "bad".toByteArray(), "/out.txt" to byteArrayOf(), "info.prop/other.txt" to byteArrayOf(), "binary.dat" to byteArrayOf(0, 1), "bad.txt" to byteArrayOf(0xC3.toByte())).forEach { entry ->
            try { PluginArchive.read(ByteArrayInputStream(zip(main, info, entry))); fail(entry.first) } catch (_: IllegalArgumentException) { } catch (_: java.nio.charset.CharacterCodingException) { }
        }
        try { PluginArchive.read(ByteArrayInputStream(zip(main, info, "notes" to "text".toByteArray(), "notes/x.txt" to "x".toByteArray()))); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun missingUsageIsNeverInvented() {
        val missing = usageLabel("{}")
        assertTrue(missing.contains("合计 未提供")); assertTrue(missing.contains("缓存写入 未提供"))
        val legacy = usageLabel("{\"prompt_tokens\":12,\"completion_tokens\":4,\"total_tokens\":16}")
        assertTrue(legacy.contains("输入总量 12")); assertTrue(legacy.contains("输入（未缓存）未提供"))
        assertTrue(usageLabel("{\"input_tokens\":8,\"output_tokens\":3}").contains("合计 未提供"))
    }
}
