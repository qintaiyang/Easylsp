package io.github.easylsp.app.platform.analysis

import com.android.tools.smali.baksmali.Adaptors.ClassDefinition
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.*
import io.github.easylsp.app.data.SampleEntity
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.security.IJadxSecurity
import jadx.core.xmlgen.BinaryXMLParser
import kotlinx.coroutines.*
import org.json.*
import java.io.*
import java.util.zip.ZipFile

class AnalysisEngine(private val xmlSecurity: IJadxSecurity = AndroidJadxSecurity()) {
    private data class DexSource(val apk: File, val entry: String) { val source get() = "${apk.name}!$entry" }
    private data class IndexedClass(val dex: DexSource, val type: String) { val source get() = dex.source }
    private var sample: SampleEntity? = null
    private var apks = emptyList<File>()
    private var sources = emptyList<DexSource>()
    private var classes: List<IndexedClass>? = null
    private var decompiler: JadxDecompiler? = null
    private var javaInput: File? = null
    private var loadMillis = 0L
    private var sampledPeakHeapBytes = 0L
    private fun sampleHeap() { val runtime = Runtime.getRuntime(); sampledPeakHeapBytes = maxOf(sampledPeakHeapBytes, runtime.totalMemory() - runtime.freeMemory()) }
    private val xmlRoot by lazy { jadx.core.dex.nodes.RootNode(JadxArgs().apply { security = xmlSecurity }) }
    private fun clear() { releaseJava(); classes = null; sources = emptyList(); apks = emptyList(); sample = null; loadMillis = 0; sampledPeakHeapBytes = 0 }
    private fun load(next: SampleEntity) {
        if (sample?.projectId == next.projectId && sample?.revision == next.revision) return
        clear()
        val files = JSONArray(next.filesJson)
        apks = (0 until files.length()).map { File(files.getString(it)) }
        sources = apks.flatMap { apk -> ZipFile(apk).use { zip ->
            zip.entries().asSequence().filter { it.name.matches(Regex("classes(?:[0-9]+)?\\.dex")) }
                .map { DexSource(apk, it.name) }.toList()
        } }
        sample = next
    }
    // Open exactly one ZIP entry. ZipDexContainer enumerates and retains every DEX byte array.
    private fun readDex(source: DexSource): DexBackedDexFile = ZipFile(source.apk).use { zip ->
        zip.getInputStream(requireNotNull(zip.getEntry(source.entry))).buffered().use {
            DexBackedDexFile.fromInputStream(Opcodes.getDefault(), it).also { sampleHeap() }
        }
    }
    private suspend fun index(progress: (String) -> Unit): List<IndexedClass> {
        classes?.let { return it }
        val start = System.currentTimeMillis()
        val result = buildList {
            for (source in sources) {
                currentCoroutineContext().ensureActive()
                progress("索引 ${source.source}")
                readDex(source).classes.forEach { add(IndexedClass(source, it.type)) }
            }
        }
        classes = result
        loadMillis = System.currentTimeMillis() - start
        return result
    }
    private class QueryPage(private val offset: Int, private val limit: Int) {
        private val items = JSONArray()
        private var total = 0
        fun add(row: () -> JSONObject) {
            if (total >= offset && items.length() < limit) items.put(row())
            total++
        }
        fun json(): JSONObject = JSONObject().put("items", items).put("total", total).put("offset", offset)
            .put("nextOffset", if (offset + items.length() < total) offset + items.length() else JSONObject.NULL)
    }
    private fun releaseJava() { decompiler?.close(); decompiler = null; javaInput?.delete(); javaInput = null }
    private suspend fun jadx(source: DexSource, progress: (String) -> Unit): JadxDecompiler {
        decompiler?.let { return it }
        progress("初始化 JADX · ${source.source}")
        val input = File.createTempFile("easylsp-source-", ".dex")
        javaInput = input
        ZipFile(source.apk).use { zip -> zip.getInputStream(requireNotNull(zip.getEntry(source.entry))).use { stream -> input.outputStream().use { stream.copyTo(it) } } }
        val instance = JadxDecompiler(JadxArgs().apply { inputFiles = listOf(input); isSkipResources = true; threadsCount = 1; pluginLoader = BundledJadxPlugins(); security = xmlSecurity })
        try { runInterruptible { instance.load(); sampleHeap() }; decompiler = instance; return instance }
        catch (error: Throwable) { instance.close(); throw error }
    }
    suspend fun execute(next: SampleEntity, action: String, args: JSONObject, progress: (String) -> Unit): JSONObject {
        if (action == "clear") { clear(); return JSONObject().put("cleared", true) }
        load(next)
        val offset = args.optInt("offset", 0); val limit = args.optInt("limit", 50)
        require(offset >= 0 && limit in 1..200) { "offset >= 0，limit 必须为 1..200" }
        val q = args.optString("query")
        val owner = args.optString("owner").takeIf { it.isNotBlank() }?.let(DexSignatures::owner)
        val page = QueryPage(offset, limit)
        when (action) {
            "classes", "methods", "fields", "strings", "references" -> {
                if (action == "strings") require(q.isNotEmpty()) { "字符串查询不能为空" }
                val target = args.optString("target")
                val indexed = index(progress)
                if (action == "classes") {
                    indexed.forEach { cls ->
                        currentCoroutineContext().ensureActive()
                        if ((owner == null || cls.type == owner) && (cls.type.contains(q, true) || DexSignatures.type(cls.type).contains(q, true)))
                            page.add { base(cls).put("signature", cls.type).put("name", DexSignatures.type(cls.type)).put("kind", "class") }
                    }
                } else for (source in sources) {
                    if (owner != null && indexed.none { it.dex == source && it.type == owner }) continue
                    currentCoroutineContext().ensureActive()
                    progress("查询 ${source.source}")
                    for (cls in readDex(source).classes) {
                        currentCoroutineContext().ensureActive()
                        if (owner != null && cls.type != owner) continue
                        val location = IndexedClass(source, cls.type)
                        if (action == "fields") for (field in cls.fields) {
                            if (DexSignatures.field(field).contains(q, true)) page.add { base(location).put("signature", DexSignatures.field(field)).put("name", field.name).put("type", DexSignatures.type(field.type)).put("kind", "field") }
                        } else for (method in cls.methods) {
                            val signature = DexSignatures.method(method)
                            fun row() = base(location).put("signature", signature).put("name", method.name).put("javaSignature", DexSignatures.javaMethod(method)).put("parameterTypes", JSONArray(method.parameterTypes.map { DexSignatures.type(it.toString()) })).put("returnType", DexSignatures.type(method.returnType)).put("kind", "method")
                            when (action) {
                                "methods" -> if (signature.contains(q, true) || DexSignatures.javaMethod(method).contains(q, true)) page.add { row() }
                                "strings" -> {
                                    val hits = strings(method).filter { it.contains(q) }
                                    if (hits.isNotEmpty()) page.add { row().put("strings", JSONArray(hits)) }
                                }
                                "references" -> {
                                    var pc = 0
                                    for (instruction in method.implementation?.instructions ?: emptyList()) {
                                        val ref = (instruction as? ReferenceInstruction)?.reference
                                        val reference = when (ref) { is MethodReference -> DexSignatures.method(ref); is FieldReference -> DexSignatures.field(ref); is TypeReference -> ref.type; else -> null }
                                        if (reference == target) { val position = pc; page.add { row().put("target", target).put("codeOffset", position) } }
                                        pc += instruction.codeUnits
                                    }
                                }
                            }
                        }
                    }
                }
            }
            "java", "smali" -> {
                val className = args.getString("className")
                val indexed = index(progress).firstOrNull { it.type == DexSignatures.owner(className) && (args.optString("source").isBlank() || it.source == args.getString("source")) } ?: error("样本中未找到类 $className")
                val code = try { if (action == "java") {
                    val java = jadx(indexed.dex, progress).searchJavaClassByOrigFullName(DexSignatures.type(indexed.type)) ?: error("JADX 未加载该类，请打开 Smali")
                    progress("还原 ${java.fullName}")
                    runInterruptible {
                        val signature = args.optString("signature")
                        if (signature.isBlank() || !signature.contains("->")) java.code else {
                            java.decompile()
                            val method = java.searchMethodByShortId(signature.substringAfter("->")) ?: error("JADX 未还原该方法，请打开同一方法的 Smali")
                            method.codeStr
                        }
                    }
                } else {
                    val output = StringWriter()
                    ClassDefinition(BaksmaliOptions(), readDex(indexed.dex).classes.first { it.type == indexed.type }).writeTo(BaksmaliWriter(output))
                    val full = output.toString()
                    val method = args.optString("signature")
                    if (method.isBlank()) full else {
                        val short = method.substringAfter("->")
                        val lines = full.lines(); val start = lines.indexOfFirst { it.startsWith(".method ") && it.substringAfterLast(' ') == short }
                        require(start >= 0) { "Smali 中没有指定方法 $method" }
                        val end = (start until lines.size).first { lines[it] == ".end method" }
                        lines.subList(start, end + 1).joinToString("\n")
                    }
                }
                } finally { sampleHeap(); releaseJava() }
                require(code.isNotBlank()) { "JADX 未生成此类或方法的独立 Java 代码，请打开同一目标的 Smali" }
                return textPage(code, offset, limit).put("source", indexed.source).put("className", className).put("format", action).put("partial", action == "java" && (code.contains("JADX ERROR") || code.contains("Method not decompiled"))).withSample(next)
            }
            "manifest" -> {
                val file = apks.first()
                val xml = ZipFile(file).use { zip -> zip.getInputStream(requireNotNull(zip.getEntry("AndroidManifest.xml"))).buffered().use { BinaryXMLParser(xmlRoot).parse(it).codeStr } }
                return textPage(xml, offset, limit).put("source", "${file.name}!AndroidManifest.xml").withSample(next)
            }
            "resources" -> for (apk in apks) ZipFile(apk).use { zip -> zip.entries().asSequence().filter { !it.isDirectory && (it.name.startsWith("res/") || it.name.startsWith("assets/") || it.name == "resources.arsc") && it.name.contains(q, true) }.forEach { page.add { JSONObject().put("source", apk.name).put("name", it.name).put("size", it.size) } } }
            "resource" -> {
                val apk = apks.single { it.name == args.getString("apk") }
                return ZipFile(apk).use { zip ->
                    val entry = requireNotNull(zip.getEntry(args.getString("entry"))) { "资源不存在" }
                    require(entry.size <= 2 * 1024 * 1024) { "资源过大，请导出 APK 后查看" }
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val text = when {
                        entry.name.endsWith(".xml") && bytes.firstOrNull() != '<'.code.toByte() -> BinaryXMLParser(xmlRoot).parse(ByteArrayInputStream(bytes)).codeStr
                        entry.name.endsWith(".xml") || entry.name.endsWith(".txt") || entry.name.endsWith(".json") || entry.name.endsWith(".properties") -> bytes.toString(Charsets.UTF_8)
                        else -> return@use JSONObject().put("binary", true).put("name", entry.name).put("size", bytes.size).withSample(next)
                    }
                    textPage(text, offset, limit).put("name", entry.name).withSample(next)
                }
            }
            "info" -> return JSONObject().put("classes", index(progress).size).put("dexSources", JSONArray(sources.map { it.source })).put("apks", JSONArray(apks.map { it.name })).put("indexMillis", loadMillis).put("pssKiB", android.os.Debug.getPss()).put("sampledPeakHeapBytes", sampledPeakHeapBytes).put("maxHeapBytes", Runtime.getRuntime().maxMemory()).withSample(next)
            else -> error("未知分析操作: $action")
        }
        return page.json().withSample(next)
    }
    private fun strings(method: Method): List<String> = method.implementation?.instructions?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }?.distinct() ?: emptyList()
    private fun base(indexed: IndexedClass) = JSONObject().put("source", indexed.source).put("owner", DexSignatures.type(indexed.type))
    private fun JSONObject.withSample(next: SampleEntity): JSONObject = put("projectId", next.projectId).put("sampleRevision", next.revision).put("version", next.version)
    private fun textPage(text: String, offset: Int, limit: Int): JSONObject {
        val lines = text.lines(); val page = lines.drop(offset).take(limit)
        return JSONObject().put("text", page.joinToString("\n")).put("startLine", offset + 1).put("totalLines", lines.size).put("nextOffset", if (offset + page.size < lines.size) offset + page.size else JSONObject.NULL)
    }
}
