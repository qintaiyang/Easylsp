package io.github.easylsp.app.platform.diagnostics

import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.data.DiagnosticEntity
import io.github.easylsp.app.platform.analysis.hex
import io.github.easylsp.app.platform.analysis.nativeAddress
import io.github.easylsp.app.platform.root.RootCommand
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One bounded root receiver per run. The target callbacks never depend on the manager Binder. */
class NativeObservation(private val graph: EasylspApp, private val row: DiagnosticEntity, private val target: DiagnosticTarget) {
    val socketName = "easylsp-${row.id}"
    private val dir = graph.diagnostics.store.directory(row.projectId, row.id)
    private val ready = File(dir, "raw/native.ready")
    private val errors = File(dir, "raw/native-error.txt")
    private var job: Job? = null
    private var failure: String? = null

    suspend fun start(scope: CoroutineScope, duration: Int) {
        val executable = File(graph.filesDir, "linux/bin/diagnostic-collector").apply { parentFile!!.mkdirs() }
        graph.assets.open("namespace/arm64-v8a/easylsp-diagnostic-collector").use { input -> executable.outputStream().use { input.copyTo(it) } }
        check(executable.setExecutable(true, true))
        errors.writeText("")
        val argv = listOf(executable.path, socketName, target.uid.toString(), target.packageName, target.processName, (duration + 15).toString(), ready.path)
        val script = "exec " + argv.joinToString(" ", transform = RootCommand::quote) + " 2>" + RootCommand.quote(errors.path)
        job = scope.launch(Dispatchers.IO) {
            try {
                File(dir, "native-events.jsonl").bufferedWriter().use { writer ->
                    val result = graph.executions.runner.execute(UUID.randomUUID().toString(), RootCommand.wrap(listOf("/system/bin/sh", "-c", script), true, graph.root.su), dir,
                        closeInput = true, timeoutSeconds = duration + 20) { writer.write(it); writer.flush() }
                    if (result.exitCode != 0) failure = "collector exit=${result.exitCode}"
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { failure = error.toString() }
        }
        val started = withTimeoutOrNull(5000) { while (!ready.exists() && job!!.isActive) delay(25); ready.exists() } == true
        check(started) { "native collector 未就绪：$failure ${errors.readText()}" }
    }

    suspend fun finish(report: JSONObject) {
        job?.cancelAndJoin()
        val initialized = JSONArray(); val probes = JSONArray(); val exits = JSONArray()
        val modules = mutableMapOf<String, MutableList<JSONObject>>()
        var count = 0
        val drops = mutableMapOf<String, Long>()
        val events = File(dir, "native-events.jsonl")
        if (events.exists()) events.useLines { lines -> lines.forEachIndexed { index, line ->
            val event = JSONObject(line).put("diagnosticRunId", row.id).put("source", "native-events.jsonl").put("line", index + 1)
            count++
            val identity = "${event.getInt("pid")}:${event.getLong("startTicks")}"
            drops[identity] = maxOf(drops[identity] ?: 0, event.getLong("droppedBefore"))
            when (event.getString("phase")) {
                "native_initialized" -> initialized.put(event)
                "native_probe" -> probes.put(event)
                "library_existing", "library_before_init", "library_after_init" -> {
                    val list = modules.getOrPut(identity) { mutableListOf() }
                    list.removeAll { it.getString("start") == event.getString("start") }
                    list += event
                }
                "library_before_fini" -> modules[identity]?.removeAll { it.getString("start") == event.getString("start") }
                "native_exit_call" -> {
                    val pc = nativeAddress(event.getString("callerReturnAddress"))
                    val candidates = modules[identity].orEmpty().filter { pc >= nativeAddress(it.getString("start")) && pc < nativeAddress(it.getString("end")) }
                    if (candidates.size == 1) {
                        val module = candidates.single()
                        event.put("callerModule", module.getString("name")).put("loadBias", module.getString("loadBias"))
                            .put("callerElfVaddr", hex(pc - nativeAddress(module.getString("loadBias"))))
                            .put("moduleEventLine", module.getInt("line")).put("addressBasis", "dl_phdr_info range in this PID/startTicks; raw return address, no subtraction or PAC stripping")
                    }
                    exits.put(event)
                }
            }
            graph.diagnostics.store.event(row, event)
        } }
        val observed = (0 until initialized.length()).any { initialized.getJSONObject(it).getInt("errorCode") == 0 }
        report.put("nativeEngine", if (observed) "OBSERVED" else "UNCONFIRMED")
            .put("nativeObservation", JSONObject().put("backend", "ShadowHook 2.0.0 / inline UNIQUE").put("events", count)
                .put("initialized", initialized).put("probes", probes).put("exitCalls", exits).put("reportedDrops", drops.values.sum())
                .put("collectorError", failure ?: JSONObject.NULL).put("collectorStderr", errors.takeIf { it.exists() }?.readText().orEmpty())
                .put("artifact", "native-events.jsonl"))
        report.getJSONArray("limitations").put("native inline 观测会修改全进程函数入口；直接系统调用、引擎安装前的退出不保证命中。返回地址保留原值；库范围关联不等同于 Build ID 匹配。末尾事件丢失可能无法计数。")
    }
}
