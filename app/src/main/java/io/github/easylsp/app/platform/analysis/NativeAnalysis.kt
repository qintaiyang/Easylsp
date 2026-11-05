package io.github.easylsp.app.platform.analysis

import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.platform.root.RootCommand
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

class NativeAnalysis(private val graph: EasylspApp) {
    private fun sampleFiles(project: String, revision: Long): List<File> {
        val row = requireNotNull(graph.store.dao.sample(project, revision)) { "样本不存在；先 capture_sample，再显式使用返回的 sampleRevision（会话绑定不会自动更新）" }
        val files = JSONArray(row.filesJson)
        return (0 until files.length()).map { File(files.getString(it)) }
    }
    private fun elf(zip: ZipFile, entry: java.util.zip.ZipEntry) = NativeElf.read(entry.size) { offset, count ->
        zip.getInputStream(entry).use { stream ->
            var remaining = offset
            while (remaining > 0) { val skipped = stream.skip(remaining); require(skipped > 0) { "ELF entry 不完整" }; remaining -= skipped }
            stream.readNBytes(count).also { require(it.size == count) { "ELF entry 不完整" } }
        }
    }
    private fun elf(file: File) = RandomAccessFile(file, "r").use { input -> NativeElf.read(input.length()) { offset, count -> input.seek(offset); ByteArray(count).also(input::readFully) } }
    fun modules(project: String, revision: Long, diagnosticId: String? = null): JSONObject {
        val report = diagnosticId?.let { graph.diagnostics.read(project, it).also { report -> require(report.getLong("sampleRevision") == revision) { "诊断与样本版本不一致" } } }
        val entries = JSONArray()
        sampleFiles(project, revision).forEachIndexed { index, apk -> ZipFile(apk).use { zip ->
            zip.entries().asSequence().filter { it.name.matches(Regex("lib/arm64-v8a/[^/]+\\.so")) }.forEach { entry ->
                val item = JSONObject().put("moduleId", "$index:${entry.name}").put("apkIndex", index).put("sourceApk", apk.name).put("entry", entry.name)
                    .put("name", entry.name.substringAfterLast('/')).put("abi", "arm64-v8a").put("bytes", entry.size).put("sampleRevision", revision)
                try {
                    val metadata = elf(zip, entry)
                    item.put("elf", metadata.json())
                    val observed = mutableListOf<String>()
                    val tombstones = report?.getJSONArray("tombstones") ?: JSONArray()
                    for (i in 0 until tombstones.length()) {
                        val frames = tombstones.getJSONObject(i).getJSONArray("frames")
                        for (j in 0 until frames.length()) {
                            val frame = frames.getJSONObject(j)
                            if (frame.getString("module").substringAfterLast('/').substringAfterLast('!') == item.getString("name") && !frame.isNull("buildId")) observed += frame.getString("buildId")
                        }
                    }
                    item.put("observedBuildIds", JSONArray(observed.distinct())).put("matchStatus", when {
                        observed.isEmpty() || metadata.buildId == null -> "UNCONFIRMED"
                        metadata.buildId in observed -> "BUILD_ID_MATCH"
                        else -> "BUILD_ID_MISMATCH"
                    })
                } catch (error: IllegalArgumentException) { item.put("error", error.message).put("matchStatus", "UNSUPPORTED_ELF") }
                entries.put(item)
            }
        } }
        val result = JSONObject().put("projectId", project).put("sampleRevision", revision).put("modules", entries)
            .put("note", "仅枚举样本内 arm64 SO；动态释放或匿名映射可能不在 APK 中。文件同名不代表匹配。")
        if (diagnosticId != null) File(graph.diagnostics.store.directory(project, diagnosticId), "modules.json").writeText(result.toString(2))
        return result
    }
    private data class Module(val file: File, val apk: File, val apkIndex: Int, val entry: String, val elf: NativeElf, val revision: Long)
    @Synchronized private fun module(project: String, revision: Long, id: String): Module {
        val index = id.substringBefore(':').toInt()
        val entry = id.substringAfter(':')
        require(entry.matches(Regex("lib/arm64-v8a/[^/]+\\.so"))) { "moduleId 必须来自 list_native_modules" }
        val apk = sampleFiles(project, revision).getOrNull(index) ?: error("moduleId APK 索引无效")
        val base = graph.executions.directory(project)
        val file = File(base, "native/$revision/$index/$entry")
        if (!file.exists()) ZipFile(apk).use { zip ->
            val item = requireNotNull(zip.getEntry(entry)) { "样本中没有该 SO" }
            file.parentFile!!.mkdirs()
            val temporary = File(file.parentFile, file.name + ".tmp")
            zip.getInputStream(item).use { input -> temporary.outputStream().use { input.copyTo(it) } }
            check(temporary.renameTo(file))
        }
        return Module(file, apk, index, entry, elf(file), revision)
    }
    /** APK direct maps use archive offsets. ZIP entry data starts after its local header. */
    internal fun zipDataOffset(apk: File, entry: String): Long = RandomAccessFile(apk, "r").use { input ->
        fun u16(): Int = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
        fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)
        var eocd = input.length() - 22
        val lower = maxOf(0, input.length() - 65557)
        while (eocd >= lower) { input.seek(eocd); if (u32() == 0x06054b50L) break; eocd-- }
        require(eocd >= lower) { "APK ZIP 目录缺失" }
        input.seek(eocd + 10); val count = u16(); val centralSize = u32(); val central = u32()
        require(count != 65535 && central != 0xffffffffL && central + centralSize <= input.length()) { "首版不支持 ZIP64 APK" }
        input.seek(central)
        repeat(count) {
            val start = input.filePointer
            require(u32() == 0x02014b50L) { "APK ZIP 目录无效" }
            input.seek(start + 28); val nameSize = u16(); val extraSize = u16(); val commentSize = u16()
            input.seek(start + 42); val local = u32()
            val name = ByteArray(nameSize).also(input::readFully).toString(Charsets.UTF_8)
            if (name == entry) {
                input.seek(local); require(u32() == 0x04034b50L)
                input.seek(local + 26); val localName = u16(); val localExtra = u16()
                return@use local + 30 + localName + localExtra
            }
            input.seek(start + 46 + nameSize + extraSize + commentSize)
        }
        error("APK ZIP entry 不存在")
    }
    private fun resolve(project: String, module: Module, args: JSONObject): JSONObject {
        val original = args.getString("address")
        val address = nativeAddress(original)
        val kind = args.getString("addressKind")
        val result = JSONObject().put("inputAddress", original).put("addressKind", kind).put("transformations", JSONArray())
        var expected = args.optString("expectedBuildId").takeIf { it.isNotEmpty() }
        val report = args.optString("diagnosticRunId").takeIf { it.isNotEmpty() }?.let { graph.diagnostics.read(project, it) }
        if (report != null) {
            require(report.getLong("sampleRevision") == module.revision) { "样本 revision 与诊断不一致" }
            val tombstones = report.getJSONArray("tombstones")
            val buildIds = mutableSetOf<String>()
            for (i in 0 until tombstones.length()) {
                val frames = tombstones.getJSONObject(i).getJSONArray("frames")
                for (j in 0 until frames.length()) {
                    val frame = frames.getJSONObject(j)
                    if (frame.getString("module").substringAfterLast('/').substringAfterLast('!') == module.file.name && !frame.isNull("buildId")) buildIds += frame.getString("buildId")
                }
            }
            if (expected == null && buildIds.size == 1) expected = buildIds.single()
        }
        val match = if (expected == null || module.elf.buildId == null) "UNCONFIRMED" else if (expected.equals(module.elf.buildId, true)) "BUILD_ID_MATCH" else "BUILD_ID_MISMATCH"
        require(match != "BUILD_ID_MISMATCH") { "Build ID 不匹配：证据 $expected，样本 ${module.elf.buildId}；不能用这个 SO 解释故障指令" }
        val vaddr = when (kind) {
            "ELF_VADDR", "TOMBSTONE_RELATIVE_PC" -> address
            "FILE_OFFSET" -> module.elf.virtualAddress(address)
            "RUNTIME_PC" -> {
                requireNotNull(report) { "运行时 PC 需要 diagnosticRunId 和该次进程映射" }
                val processes = report.getJSONArray("processes")
                val process = (0 until processes.length()).map { processes.getJSONObject(it) }.singleOrNull { it.getInt("pid") == args.getInt("pid") && it.getLong("startTicks") == args.getLong("startTicks") }
                    ?: error("没有该 PID/startTicks 的进程身份；不能猜测 load bias")
                val maps = File(graph.diagnostics.store.directory(project, report.getString("diagnosticRunId")), process.getString("maps")).readLines()
                val mapping = maps.mapNotNull { Regex("^([0-9a-f]+)-([0-9a-f]+)\\s+([-rwxps]+)\\s+([0-9a-f]+)\\s+\\S+\\s+\\d+\\s*(.*)$").find(it) }
                    .singleOrNull { address >= nativeAddress(it.groupValues[1]) && address < nativeAddress(it.groupValues[2]) } ?: error("运行时 PC 不在已保存映射中")
                val path = mapping.groupValues[5].removeSuffix(" (deleted)")
                var offset = nativeAddress(mapping.groupValues[4])
                if (path.endsWith(".apk")) {
                    val apks = report.optJSONArray("installedApks") ?: error("此旧诊断没有记录安装 APK 路径，无法确认直接映射的 APK 来源")
                    require(path == apks.getString(module.apkIndex)) { "映射 APK 与所选 SO 来源不一致" }
                    val zipOffset = zipDataOffset(module.apk, module.entry)
                    offset -= zipOffset
                    result.put("zipEntryDataOffset", hex(zipOffset))
                } else require(path.substringAfterLast('/') == module.file.name) { "映射来自 $path，不是所选 SO；匿名/释放库需要对应的实际 ELF" }
                require(offset >= 0) { "APK 文件偏移早于所选 ELF entry" }
                val bias = module.elf.loadBias(nativeAddress(mapping.groupValues[1]), offset, report.getLong("pageSize"), mapping.groupValues[3].contains('x'))
                result.put("runtimePc", original).put("loadBias", hex(bias)).put("mapping", mapping.value)
                address - bias
            }
            else -> error("addressKind 必须为 ELF_VADDR/TOMBSTONE_RELATIVE_PC/RUNTIME_PC/FILE_OFFSET")
        }
        require(module.elf.contains(vaddr)) { "地址不在所选 ELF 的 PT_LOAD 范围；不自动去 PAC/tag，也不把 PC 减 4" }
        return result.put("elfVaddr", hex(vaddr)).put("fileOffset", module.elf.fileOffset(vaddr)?.let(::hex) ?: JSONObject.NULL)
            .put("buildId", module.elf.buildId ?: JSONObject.NULL).put("matchStatus", match)
    }
    suspend fun prepareTools(project: String): JSONObject {
        val command = graph.linux.command(project, "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y llvm")
        val id = graph.executions.start(project, "native-tools", "安装 Ubuntu LLVM 分析工具") { id, output -> graph.executions.runner.execute(id, command, graph.executions.directory(project), closeInput = true, timeoutSeconds = 600, output = output).exitCode }
        return JSONObject().put("executionId", id)
    }
    private suspend fun llvm(project: String, module: Module, arguments: List<String>, diagnosticId: String?, input: File = module.file): JSONObject {
        val base = graph.executions.directory(project)
        val relative = input.relativeTo(base).invariantSeparatorsPath
        val argv = arguments.map { if (it == "@MODULE") "/workspace/$project/$relative" else it }
        val command = graph.linux.command(project, "command -v ${RootCommand.quote(argv.first())} >/dev/null || { echo 'LLVM 未安装：调用 prepare_native_tools 后读取同一 executionId'; exit 127; }; " + argv.joinToString(" ", transform = RootCommand::quote))
        val execution = graph.executions.start(project, "native-analysis", argv.first() + " " + module.file.name) { id, output ->
            graph.executions.runner.execute(id, command, base, closeInput = true, timeoutSeconds = 60, output = output).exitCode
        }
        val result = graph.executions.await(project, execution).put("executionId", execution).put("arguments", JSONArray(argv))
        if (diagnosticId != null) {
            val directory = File(graph.diagnostics.store.directory(project, diagnosticId), "disassembly").apply { mkdirs() }
            val output = File(directory, "$execution.txt")
            graph.executions.store.file(execution).copyTo(output)
            result.put("artifact", "disassembly/${output.name}")
        }
        return result
    }
    suspend fun inspect(project: String, revision: Long, id: String): JSONObject {
        val module = module(project, revision, id)
        return llvm(project, module, listOf("llvm-readelf", "--file-header", "--program-headers", "--notes", "--dynamic", "--dyn-syms", "@MODULE"), null).put("elf", module.elf.json())
    }
    suspend fun symbolize(project: String, revision: Long, id: String, args: JSONObject): JSONObject {
        val module = module(project, revision, id)
        val address = resolve(project, module, args)
        val result = llvm(project, module, listOf("llvm-addr2line", "-f", "-C", "-i", "-e", "@MODULE", address.getString("elfVaddr")), args.optString("diagnosticRunId").takeIf { it.isNotEmpty() })
        return result.put("address", address).put("note", "?? 表示没有调试信息；不生成虚构源码行。UNCONFIRMED 只说明此样本在该地址的内容。")
    }
    suspend fun disassemble(project: String, revision: Long, id: String, args: JSONObject): JSONObject {
        val module = module(project, revision, id)
        val address = resolve(project, module, args)
        val radius = args.optInt("radiusBytes", 64)
        require(radius in 4..1024) { "radiusBytes 必须为 4..1024" }
        val pc = nativeAddress(address.getString("elfVaddr"))
        require(module.elf.fileOffset(pc) != null) { "地址只有内存覆盖，没有可读取的文件指令" }
        val diagnosticId = args.optString("diagnosticRunId").takeIf { it.isNotEmpty() }
        val arguments = listOf("llvm-objdump", "-d", "--demangle", "--start-address=${hex(maxOf(0, pc - radius) / 4 * 4)}", "--stop-address=${hex(pc + radius)}", "@MODULE")
        var result = llvm(project, module, arguments, diagnosticId)
        // Real packed libDexHelper has PT_LOAD code but no usable executable section for objdump.
        // Do not hide tool failures. Only a successful command with no instructions takes this path.
        if (result.optInt("exitCode", -1) == 0 && !Regex("(?m)^\\s*[0-9a-f]+:\\s+[0-9a-f]{8}\\s").containsMatchIn(result.getString("text"))) {
            val sectionExecution = result.getString("executionId")
            val segment = module.elf.loads.first { pc >= it.vaddr && pc - it.vaddr < it.fileSize }
            val start = (maxOf(segment.vaddr, pc - radius) + 3) / 4 * 4
            val end = minOf(segment.vaddr + segment.fileSize, pc + radius) / 4 * 4
            require(start <= pc && end > pc) { "PT_LOAD 文件中没有该地址的完整 AArch64 指令" }
            val offset = segment.offset + start - segment.vaddr
            val bytes = RandomAccessFile(module.file, "r").use { file -> file.seek(offset); ByteArray((end - start).toInt()).also(file::readFully) }
            val window = File(module.file.parentFile, "window-${java.util.UUID.randomUUID()}.elf")
            window.writeBytes(NativeCodeWindow.elf(start, bytes))
            result = llvm(project, module, arguments, diagnosticId, window)
                .put("sectionDisassemblyExecutionId", sectionExecution).put("decodingBasis", "PT_LOAD bytes in a generated analysis-only ELF section; original symbols unavailable")
                .put("windowElfVaddr", hex(start)).put("windowFileOffset", hex(offset)).put("windowBytes", bytes.size)
        }
        return result.put("address", address).put("note", "指令窗口说明磁盘 ELF 的字节解码；不证明这条路径已执行，匿名/解密后代码不能由 APK 还原。")
    }
}
