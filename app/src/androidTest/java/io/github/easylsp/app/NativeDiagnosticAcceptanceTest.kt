package io.github.easylsp.app

import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.platform.diagnostics.DiagnosticMode
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Rule
import kotlinx.coroutines.flow.first
import io.github.easylsp.app.data.ConversationEntity
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.io.File

/** Explicit package argument only: reusable acceptance entry through the same app coordinator. */
class NativeDiagnosticAcceptanceTest {
    @get:Rule val visible = VisibleActivityRule()
    @Test fun configuredModelDiagnosesSelectedTargetThroughChat() = runBlocking(Dispatchers.IO) {
        val arguments = InstrumentationRegistry.getArguments()
        val name = requireNotNull(arguments.getString("diagnosticPackageName"))
        val providerId = requireNotNull(arguments.getString("diagnosticProviderId"))
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
        val dao = graph.store.workflows
        val provider = requireNotNull(dao.provider(providerId))
        val project = requireNotNull(graph.store.dao.projectByPackage(name))
        val id = UUID.randomUUID().toString()
        ActivityScenario.launch(MainActivity::class.java).use {
            dao.insertConversation(ConversationEntity(id, project.id, provider.id, provider.model, "Native 壳启动诊断验收", project.sampleRevision, System.currentTimeMillis()))
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("conversationId", id) })
            try {
                graph.chat.submit(id, "当前项目是用户已经勾选作用域的迅猛兔加速器。已授权启动和诊断；请直接执行，不修改框架作用域和插件配置。先读取已有启动诊断，随后用 NATIVE_OBSERVE 模式 restart=true 采集 12 秒，启动后读取同一个 executionId 和 diagnosticRunId。分析崩溃或自杀时的 native 行为：实际探针状态、库构造阶段、退出调用者、参数和系统退出证据。枚举匹配 SO，用 LLVM 查看相关模块并反汇编有证据支持的地址；如果 PC 不属于 APK 模块，明确指出，另选已知壳模块的导出函数做静态窗口示例并说明不能把它当作已执行故障点。禁止把不明 PC 硬配给 libDexHelper。输出带诊断 ID、具体事件行或文件引用的中文结论，区分观察事实、候选路径和仍未知部分。不要生成绕过插件。", null, true, JSONArray()).await()
                val turn = withTimeout(900000) { dao.turns(id).first { rows -> rows.isNotEmpty() && rows.last().finishedAt != null } }.last()
                val runs = dao.toolRuns(id).first()
                val evidence = JSONObject().put("conversationId", id).put("projectId", project.id).put("packageName", name).put("model", provider.model)
                    .put("status", turn.status).put("errorDetail", turn.errorDetail).put("tools", JSONArray(runs.map { JSONObject().put("name", it.name).put("status", it.status).put("arguments", it.arguments).put("result", it.result) }))
                File(graph.filesDir, "native-production-chat-evidence.json").writeText(evidence.toString(2))
                check(turn.status == "COMPLETE") { evidence.toString(2) }
                for (tool in listOf("start_native_diagnostic", "read_native_diagnostic", "list_native_modules", "disassemble_native")) check(runs.any { it.name == tool && it.status == "COMPLETE" }) { "未完成 $tool；$id" }
            } finally { graph.chat.stop() }
        }
    }
    @Test fun llvmAnalyzesCapturedDemoFault() = runBlocking(Dispatchers.IO) {
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
        ActivityScenario.launch(MainActivity::class.java).use {
            var project = requireNotNull(graph.store.dao.projectByPackage("io.github.easylsp.demo"))
            project = graph.samples.capture(project)
            val modules = graph.nativeAnalysis.modules(project.id, project.sampleRevision).getJSONArray("modules")
            val module = (0 until modules.length()).map { modules.getJSONObject(it) }.first { it.getString("name") == "libdemo_fault.so" }
            val inspected = graph.nativeAnalysis.inspect(project.id, project.sampleRevision, module.getString("moduleId"))
            check(inspected.getInt("exitCode") == 0) { inspected.toString() }
            val output = graph.executions.store.file(inspected.getString("executionId")).readText()
            val address = Regex("(?m)^\\s*\\d+:\\s+([0-9a-fA-F]+).*easylsp_demo_fault$").find(output)?.groupValues?.get(1) ?: error(output)
            val args = JSONObject().put("address", "0x$address").put("addressKind", "ELF_VADDR").put("expectedBuildId", module.getJSONObject("elf").getString("buildId"))
            val symbols = graph.nativeAnalysis.symbolize(project.id, project.sampleRevision, module.getString("moduleId"), args)
            val instructions = graph.nativeAnalysis.disassemble(project.id, project.sampleRevision, module.getString("moduleId"), args)
            check(symbols.getString("text").contains("easylsp_demo_fault")) { symbols.toString() }
            check(instructions.getInt("exitCode") == 0 && instructions.getString("text").contains("str")) { instructions.toString() }
            check(instructions.getJSONObject("address").getString("matchStatus") == "BUILD_ID_MATCH")
            val crash = graph.diagnostics.store.dao.list(project.id).asSequence().map { row -> graph.diagnostics.store.read(project.id, row.id) }
                .flatMap { report -> val array = report.getJSONArray("tombstones"); (0 until array.length()).asSequence().map { report to array.getJSONObject(it) } }
                .first { (_, tombstone) -> tombstone.getJSONArray("frames").toString().contains("libdemo_constructor_fault.so") }
            val crashText = File(graph.diagnostics.store.directory(project.id, crash.first.getString("diagnosticRunId")), crash.second.getString("source")).readText()
            val importedId = graph.diagnostics.importTombstone(project, crashText)
            val observedModules = graph.nativeAnalysis.modules(project.id, project.sampleRevision, importedId).getJSONArray("modules")
            val matched = (0 until observedModules.length()).map { observedModules.getJSONObject(it) }.first { it.getString("name") == "libdemo_constructor_fault.so" }
            check(matched.getString("matchStatus") == "BUILD_ID_MATCH") { matched.toString() }
            val frames = crash.second.getJSONArray("frames")
            val frame = (0 until frames.length()).map { frames.getJSONObject(it) }.first { it.getString("module").endsWith("libdemo_constructor_fault.so") }
            val crashInstructions = graph.nativeAnalysis.disassemble(project.id, project.sampleRevision, matched.getString("moduleId"), JSONObject()
                .put("diagnosticRunId", importedId).put("address", frame.getString("pc")).put("addressKind", "TOMBSTONE_RELATIVE_PC"))
            check(crashInstructions.getJSONObject("address").getString("matchStatus") == "BUILD_ID_MATCH")
            check(crashInstructions.getString("text").contains("str"))
            args.put("expectedBuildId", "mismatched-build-id")
            check(runCatching { graph.nativeAnalysis.disassemble(project.id, project.sampleRevision, module.getString("moduleId"), args) }.isFailure)
            File(graph.filesDir, "native-llvm-demo-evidence.json").writeText(JSONObject().put("module", module).put("symbols", symbols).put("instructions", instructions).put("crashInstructions", crashInstructions).toString(2))
        }
    }
    @Test fun sectionlessPackedModuleDisassemblesByLoadSegment() = runBlocking(Dispatchers.IO) {
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
        val name = requireNotNull(InstrumentationRegistry.getArguments().getString("diagnosticPackageName"))
        ActivityScenario.launch(MainActivity::class.java).use {
            val project = requireNotNull(graph.store.dao.projectByPackage(name))
            val result = graph.nativeAnalysis.disassemble(project.id, project.sampleRevision, "0:lib/arm64-v8a/libDexHelper.so",
                JSONObject().put("address", "0x2b10").put("addressKind", "ELF_VADDR").put("radiusBytes", 32))
            check(result.getInt("exitCode") == 0 && result.getString("text").contains("adrp")) { result.toString(2) }
            check(result.getString("text").contains("2b10:")) { result.toString(2) }
            check(result.getString("decodingBasis").startsWith("PT_LOAD"))
            check(result.getString("windowFileOffset") == "0x2af0")
            check(result.getJSONObject("address").getString("matchStatus") == "UNCONFIRMED")
            val diagnostic = graph.store.diagnostics.list(project.id).first { it.finishedAt != null && it.mode == "NATIVE_OBSERVE" }
            val tools = graph.tools.builtins(io.github.easylsp.app.platform.tools.RunBinding(project, project.sampleRevision, null, ""))
            val exported = tools.first { it.name == "export_native_report" }.invoke(JSONObject().put("diagnosticRunId", diagnostic.id))
            java.util.zip.ZipFile(exported.getString("path")).use { zip ->
                check(zip.getEntry("manifest.json") != null && zip.getEntry("native-events.jsonl") != null)
                check(zip.getEntry("raw/logcat-window.txt") == null)
            }
            File(graph.filesDir, "native-packed-window-evidence.json").writeText(result.put("reportExport", exported).toString(2))
        }
    }
    @Test fun prepareNativeAnalysis() = runBlocking(Dispatchers.IO) {
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
        val name = requireNotNull(InstrumentationRegistry.getArguments().getString("diagnosticPackageName"))
        ActivityScenario.launch(MainActivity::class.java).use {
            val project = requireNotNull(graph.store.dao.projectByPackage(name))
            val installation = graph.nativeAnalysis.prepareTools(project.id)
            val installed = withTimeout(660000) { graph.executions.await(project.id, installation.getString("executionId")) }
            check(installed.getInt("exitCode") == 0) { installed.toString(2) }
            val modules = graph.nativeAnalysis.modules(project.id, project.sampleRevision)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("modules", modules.toString()) })
        }
    }
    @Test fun captureSelectedTarget() = runBlocking(Dispatchers.IO) {
        val arguments = InstrumentationRegistry.getArguments()
        val name = requireNotNull(arguments.getString("diagnosticPackageName"))
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
        ActivityScenario.launch(MainActivity::class.java).use {
            val info = graph.packageManager.getPackageInfo(name, 0)
            val app = requireNotNull(info.applicationInfo)
            var project = graph.store.select(name, app.loadLabel(graph.packageManager).toString(), info.versionName.orEmpty())
            if (arguments.getString("captureSample", "true") == "true") project = graph.samples.capture(project)
            val mode = DiagnosticMode.valueOf(arguments.getString("diagnosticMode", "EXTERNAL_ONLY"))
            val result = graph.diagnostics.start(project, project.sampleRevision, mode, "*", arguments.getString("diagnosticDuration", "15").toInt(), true)
            withTimeout(120000) { graph.executions.await(project.id, result.getString("executionId")) }
            val report = graph.diagnostics.store.read(project.id, result.getString("diagnosticRunId"))
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("diagnosticRunId", result.getString("diagnosticRunId")); putString("projectId", project.id)
                putString("report", report.toString())
            })
            check(report.getString("state") == "COMPLETE") { report.toString(2) }
        }
    }
}
