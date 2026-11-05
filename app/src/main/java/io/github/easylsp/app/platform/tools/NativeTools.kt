package io.github.easylsp.app.platform.tools

import io.github.easylsp.app.EasylspApp
import org.json.JSONArray
import org.json.JSONObject

class NativeTools(private val graph: EasylspApp) {
    fun definitions(binding: RunBinding): List<ToolDefinition> {
        val project = binding.project.id
        val tools = mutableListOf<ToolDefinition>()
        fun revision(args: JSONObject): Long = if (args.has("sampleRevision")) args.getLong("sampleRevision")
            else if (args.has("diagnosticRunId")) graph.diagnostics.store.requireRun(project, args.getString("diagnosticRunId")).sampleRevision else binding.sampleRevision
        fun add(name: String, description: String, fields: Map<String, String>, required: List<String>, writes: Boolean = false, call: suspend (JSONObject) -> JSONObject) {
            tools += ToolDefinition(name, description, BuiltinToolSchemas.create(name, fields, required), writes = writes, invoke = call)
        }
        add("prepare_native_tools", "Install LLVM tools inside the existing Ubuntu arm64 environment. Returns executionId; wait with read_execution. Used only for offline SO analysis; does not inject code into the target.", emptyMap(), emptyList(), true) { graph.nativeAnalysis.prepareTools(project) }
        add("export_native_report", "Create a local diagnostic ZIP in this project's exports directory. Default includes reports, target events, module metadata and existing disassembly. includeRaw=true additionally selects attributed tombstone/maps text. Private paths are replaced with placeholders. Never includes APK/SO/memory, binary protobuf or system-wide logcat; does not send anything externally.",
            mapOf("diagnosticRunId" to "string", "includeRaw" to "boolean"), listOf("diagnosticRunId"), true) { args ->
            val id = args.getString("diagnosticRunId")
            val raw = args.optBoolean("includeRaw", false)
            val exporter = io.github.easylsp.app.platform.diagnostics.DiagnosticExport(graph.diagnostics.store)
            val files = exporter.files(project, id, raw)
            val base = graph.diagnostics.store.directory(project, id)
            val destination = java.io.File(graph.executions.directory(project), "exports/diagnostic-$id${if (raw) "-with-evidence" else ""}.zip").apply { parentFile!!.mkdirs() }
            exporter.write(project, id, raw, destination.outputStream())
            JSONObject().put("path", destination.path).put("bytes", destination.length()).put("files", JSONArray(files.map { it.relativeTo(base).invariantSeparatorsPath }))
                .put("includeRaw", raw).put("externalSend", false)
        }
        val sample = mapOf("sampleRevision" to "integer", "diagnosticRunId" to "string")
        add("list_native_modules", "List arm64 SOs in immutable base/split APK samples, ELF PT_LOAD metadata and existing Build IDs. Optional diagnosticRunId compares observed Build IDs and selects that run's sample revision. Native memory or private unpacked libraries absent from the sample are not silently substituted. Pagination counts modules.",
            sample + mapOf("query" to "string", "offset" to "integer", "limit" to "integer"), emptyList()) { args ->
            val result = graph.nativeAnalysis.modules(project, revision(args), args.optString("diagnosticRunId").takeIf { it.isNotEmpty() })
            val entries = result.getJSONArray("modules")
            val matching = (0 until entries.length()).map { entries.getJSONObject(it) }.filter { it.getString("name").contains(args.optString("query"), true) }
            val offset = args.optInt("offset", 0); val limit = args.optInt("limit", 50)
            require(offset >= 0 && limit in 1..200)
            result.put("modules", JSONArray(matching.drop(offset).take(limit))).put("total", matching.size).put("nextOffset", if (offset + limit < matching.size) offset + limit else JSONObject.NULL)
        }
        val module = sample + mapOf("moduleId" to "string")
        add("inspect_native_module", "Read a selected SO's ELF headers, dynamic dependencies and dynamic symbols/imports with LLVM. Use returned executionId and read_execution to page full output. Symbols/strings alone do not prove executed detection.", module, listOf("moduleId")) { args ->
            graph.nativeAnalysis.inspect(project, revision(args), args.getString("moduleId"))
        }
        val address = module + mapOf("address" to "string", "addressKind" to "string", "expectedBuildId" to "string", "pid" to "integer", "startTicks" to "integer")
        val required = listOf("moduleId", "address", "addressKind")
        add("symbolize_native_address", "Convert an address and resolve symbols/source with LLVM. addressKind=ELF_VADDR/TOMBSTONE_RELATIVE_PC/FILE_OFFSET/RUNTIME_PC. RUNTIME_PC requires diagnosticRunId, pid and startTicks with saved maps; APK mapping offsets account for ZIP entry data. Rejects known Build ID mismatches; UNCONFIRMED is not an established crash instruction. No automatic PAC/tag stripping or PC minus 4.", address, required) { args ->
            graph.nativeAnalysis.symbolize(project, revision(args), args.getString("moduleId"), args)
        }
        add("disassemble_native", "Read a bounded AArch64 instruction window around the selected address with LLVM, retaining full output and executionId for pagination. Uses the same address kinds/mapping/Build ID checks as symbolize_native_address. A disk instruction is not proof it executed; correlate with crash/probe evidence before calling a path confirmed.", address + mapOf("radiusBytes" to "integer"), required) { args ->
            graph.nativeAnalysis.disassemble(project, revision(args), args.getString("moduleId"), args)
        }
        return tools
    }
}
