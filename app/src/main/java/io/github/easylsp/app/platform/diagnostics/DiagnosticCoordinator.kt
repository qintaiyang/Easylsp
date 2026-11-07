package io.github.easylsp.app.platform.diagnostics

import android.content.Intent
import android.os.SystemClock
import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.data.*
import io.github.easylsp.app.platform.root.RootCommand
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The collector and its deadline belong to the manager, never to a Binder target or AI turn. */
class DiagnosticCoordinator(private val graph: EasylspApp) {
    val store = DiagnosticStore(graph.filesDir, graph.store.diagnostics)
    private class Active(var row: DiagnosticEntity, val target: DiagnosticTarget, val report: JSONObject) {
        val stop = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Unit>()
    }
    private val active = ConcurrentHashMap<String, Active>()
    private val starts = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recovered = scope.async {
        store.dao.all().filter { it.finishedAt == null }.forEach { row ->
            val report = store.read(row.projectId, row.id)
            report.getJSONArray("limitations").put("管理端进程中断，采集未完成；保留已写入文件，不自动重启目标。")
            store.write(row.copy(state = "INTERRUPTED", finishedAt = System.currentTimeMillis()), report)
        }
    }

    suspend fun start(project: ProjectEntity, sampleRevision: Long, mode: DiagnosticMode, processName: String,
        durationSeconds: Int, restart: Boolean): JSONObject = withContext(Dispatchers.IO) { starts.withLock {
        recovered.await()
        require(project.kind == "INSTALLED") { "诊断启动需要已安装目标" }
        require(durationSeconds in 5..120) { "采集时限必须在 5..120 秒内" }
        require(mode == DiagnosticMode.EXTERNAL_ONLY || restart) { "入口对照需要 restart=true，重新创建进程后配置才会生效" }
        val info = graph.targetApplications.info(project.packageName, project.userId)
        val target = DiagnosticTarget(project.packageName, requireNotNull(info.applicationInfo).uid, processName)
        require(active.values.none { it.target.uid == target.uid }) { "此 UID 已有进行中的诊断，请读取或停止已有诊断" }
        val sample = if (sampleRevision > 0) requireNotNull(graph.store.dao.sample(project.id, sampleRevision)) { "样本版本不存在" } else null
        val launch = if (project.userId == android.os.Process.myUid() / 100000) requireNotNull(graph.packageManager.getLaunchIntentForPackage(project.packageName)) { "目标无启动入口" } else Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(project.packageName)
        val row = DiagnosticEntity(UUID.randomUUID().toString(), project.id, sampleRevision, project.packageName, mode.name, System.currentTimeMillis())
        graph.executions.directory(project.id)
        val dir = store.directory(project.id, row.id).apply { mkdirs() }
        File(dir, "raw").mkdirs()
        val report = JSONObject().put("schemaVersion", 2).put("diagnosticRunId", row.id).put("projectId", project.id)
            .put("sampleRevision", sampleRevision).put("sampleVersion", sample?.version ?: JSONObject.NULL)
            .put("packageName", project.packageName).put("installedVersion", info.versionName).put("installedVersionCode", info.longVersionCode)
            .put("installedApks", JSONArray(listOf(info.applicationInfo!!.sourceDir) + (info.applicationInfo!!.splitSourceDirs?.toList() ?: emptyList())))
            .put("targetAbi", JSONObject.NULL).put("deviceAbis", JSONArray(android.os.Build.SUPPORTED_ABIS.toList()))
            .put("androidVersion", android.os.Build.VERSION.RELEASE).put("pageSize", android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE))
            .put("framework", graph.framework.state.value.description).put("nativeEngine", if (mode == DiagnosticMode.NATIVE_OBSERVE) "UNCONFIRMED" else "NOT_REQUESTED")
            .put("mode", mode.name).put("processScope", processName).put("uid", target.uid).put("androidUser", target.uid / 100000)
            .put("restartRequested", restart).put("createdAt", row.startedAt).put("durationSeconds", durationSeconds)
            .put("clocks", JSONObject().put("createdWallTime", row.startedAt).put("createdElapsedRealtime", SystemClock.elapsedRealtime()).put("zoneId", ZoneId.systemDefault().id))
            .put("plugins", JSONArray(graph.store.dao.plugins(project.id).map { JSONObject().put("pluginId", it.id).put("revision", it.revision)
                .put("desiredEnabled", it.desiredEnabled).put("processName", it.processName).put("processScope", it.processScope) }))
            .put("processes", JSONArray()).put("sessions", JSONArray()).put("sources", JSONArray()).put("tombstones", JSONArray()).put("exits", JSONArray())
            .put("limitations", JSONArray(listOf("短命进程可能来不及读取 /proc；缺少启动 tick 的 PID 关联仅为日志证据。", "ELF 匹配与反汇编按需调用分析工具；独立 APK 仍只有 Java 运行时。", "logcat-window.txt 包含启动窗口内系统输出，仅保留在本地原始证据目录。")))
            .put("conclusion", DiagnosticEvidence.conclusion(JSONArray(), JSONArray()))
        store.write(row, report)
        val run = Active(row, target, report)
        active[row.id] = run
        try {
            DiagnosticService.start(graph)
            val executionId = graph.executions.start(project.id, "diagnostic", "启动诊断 ${project.packageName} / ${mode.label}") { execution, output ->
                run.row = row.copy(executionId = execution, state = "COLLECTING")
                store.write(run.row, report)
                output("diagnosticRunId=${row.id}\n")
                collect(run, launch, durationSeconds, restart)
            }
            JSONObject().put("diagnosticRunId", row.id).put("executionId", executionId).put("restartRequested", restart)
        } catch (error: Exception) {
            active.remove(row.id)
            run.ended.complete(Unit)
            report.getJSONArray("limitations").put(error.toString())
            store.write(row.copy(state = "ERROR", finishedAt = System.currentTimeMillis()), report)
            throw error
        }
    } }

    fun read(projectId: String, id: String): JSONObject {
        val report = store.read(projectId, id)
        graph.nativeInstances.importDiagnostic(projectId, id, report)
        return report
    }

    fun stop(projectId: String, id: String): JSONObject {
        val row = store.requireRun(projectId, id)
        active[id]?.stop?.complete(Unit)
        return JSONObject().put("diagnosticRunId", id).put("state", row.state).put("stopRequested", active.containsKey(id))
    }
    suspend fun importTombstone(project: ProjectEntity, text: String): String = withContext(Dispatchers.IO) {
        recovered.await()
        val parsed = requireNotNull(DiagnosticEvidence.tombstone(text, "raw/imported-tombstone.txt")) { "没有识别到 Android tombstone 进程头" }
        val process = parsed.getString("processName")
        require(process == project.packageName || process.startsWith("${project.packageName}:")) { "tombstone 不属于当前项目包名" }
        val now = System.currentTimeMillis()
        val row = DiagnosticEntity(UUID.randomUUID().toString(), project.id, project.sampleRevision, project.packageName, "IMPORTED", now, now, "IMPORTED")
        graph.executions.directory(project.id)
        val raw = File(store.directory(project.id, row.id), "raw").apply { mkdirs() }
        File(raw, "imported-tombstone.txt").writeText(text)
        val tombstones = JSONArray().put(parsed)
        val report = JSONObject().put("schemaVersion", 2).put("diagnosticRunId", row.id).put("projectId", project.id).put("sampleRevision", project.sampleRevision)
            .put("packageName", project.packageName).put("mode", "IMPORTED").put("createdAt", now).put("clocks", JSONObject().put("importedAt", now))
            .put("framework", JSONObject.NULL).put("nativeEngine", "NOT_REQUESTED").put("plugins", JSONArray()).put("processes", JSONArray()).put("sessions", JSONArray())
            .put("tombstones", tombstones).put("exits", JSONArray()).put("sources", JSONArray().put(JSONObject().put("name", "tombstone").put("status", "IMPORTED").put("detail", "raw/imported-tombstone.txt")))
            .put("limitations", JSONArray(listOf("用户导入证据；未启动目标，原设备、启用插件和采集窗口未经核实。", "样本仅关联到所选项目版本，ELF 匹配尚未确认。")))
            .put("conclusion", DiagnosticEvidence.conclusion(tombstones, JSONArray()))
        store.write(row, report)
        row.id
    }
    suspend fun stopProject(projectId: String) {
        recovered.await()
        val runs = active.values.filter { it.row.projectId == projectId }
        runs.forEach { it.stop.complete(Unit) }
        runs.forEach { it.ended.await() }
    }
    fun stopAll() { active.values.forEach { it.stop.complete(Unit) } }
    fun bridgeEvent(packageName: String, processName: String, pid: Int, uid: Int, sessionId: String, phase: String, runId: String? = null) {
        scope.launch {
            active.values.filter { it.target.packageName == packageName && it.target.uid == uid && it.target.matches(processName) && (runId == null || it.row.id == runId) }.forEach { run ->
                event(run, phase, JSONObject().put("sessionId", sessionId).put("pid", pid).put("uid", uid).put("processName", processName))
            }
        }
    }
    private fun event(run: Active, phase: String, fields: JSONObject = JSONObject()) {
        store.event(run.row, fields.put("phase", phase).put("diagnosticRunId", run.row.id)
            .put("wallTime", System.currentTimeMillis()).put("elapsedRealtime", SystemClock.elapsedRealtime()))
    }
    private fun source(report: JSONObject, name: String, status: String, detail: String) {
        report.getJSONArray("sources").put(JSONObject().put("name", name).put("status", status).put("detail", detail))
    }
    private suspend fun command(dir: File, script: String, timeout: Int = 15): Pair<Int, String> {
        val text = StringBuilder()
        val result = graph.executions.runner.execute(UUID.randomUUID().toString(), RootCommand.wrap(listOf("/system/bin/sh", "-c", script), true, graph.root.su), dir,
            closeInput = true, timeoutSeconds = timeout) { text.append(it) }
        return result.exitCode to text.toString()
    }
    private suspend fun collect(run: Active, launch: Intent, duration: Int, restart: Boolean): Int = coroutineScope {
        val report = run.report
        val dir = store.directory(run.row.projectId, run.row.id)
        val raw = File(dir, "raw")
        val key = "${run.target.uid}:${run.target.packageName}"
        val zygisk = graph.store.dao.project(run.row.projectId)?.nativeBackend == "ZYGISK"
        var configured = false
        var javaConfigured = false
        var native: NativeObservation? = null
        var logger: Job? = null
        var monitor: Job? = null
        var state = "COMPLETE"
        var truncated = 0L
        var logFailure: String? = null
        var monitorFailure: String? = null
        var logReady = false
        var startWall: Long? = null
        var startElapsed: Long? = null
        var root = false
        try {
            root = graph.root.useRoot()
            report.put("root", graph.root.state.value.detail)
            if (restart) {
                require(root) { "停止目标并重新启动需要 root 授权" }
                event(run, "force_stop_requested")
                val (code, output) = command(dir, "am force-stop --user ${run.target.uid / 100000} ${RootCommand.quote(run.target.packageName)}")
                File(raw, "force-stop.txt").writeText(output)
                require(code == 0) { "停止目标失败：$output" }
            }
            if (run.row.mode == DiagnosticMode.NATIVE_OBSERVE.name) {
                require(root) { "早期 native 观测需要 root 事件接收器" }
                native = NativeObservation(graph, run.row, run.target)
                native.start(this, duration)
                event(run, "native_collector_ready")
            }
            if (run.row.mode != DiagnosticMode.EXTERNAL_ONLY.name) {
                val now = SystemClock.elapsedRealtime()
                val settings = JSONObject().put("backend", if (zygisk) "ZYGISK" else "LIBXPOSED").put("diagnosticRunId", run.row.id).put("mode", run.row.mode)
                    .put("processName", run.target.processName).put("startedElapsed", now).put("expiresElapsed", now + (duration + 30) * 1000)
                    .put("expiresAt", System.currentTimeMillis() + (duration + 30) * 1000).put("nativeSocket", native?.socketName)
                if (zygisk) { graph.nativeHost.diagnostic(run.target.uid, run.target.packageName, settings); configured = true }
                if (!zygisk || graph.framework.state.value.active) { graph.framework.publishDiagnostic(key, settings); javaConfigured = true }
                configured = true
                event(run, "configuration_published")
            }
            if (root) {
                val ready = File(raw, "logcat.ready")
                val errors = File(raw, "logcat-error.txt").apply { writeText("") }
                val since = "%.3f".format(java.util.Locale.US, System.currentTimeMillis() / 1000.0)
                logger = launch(Dispatchers.IO) {
                    try {
                        File(raw, "logcat-window.txt").bufferedWriter().use { writer ->
                            var written = 0L
                            val script = "timeout -s KILL ${duration + 30} logcat -b main -b system -b crash -b events -v epoch -v uid -T ${RootCommand.quote(since)} 2>${RootCommand.quote(errors.path)} & collector_pid=\$!; sleep 0.15; if kill -0 \$collector_pid; then echo ready > ${RootCommand.quote(ready.path)}; fi; wait \$collector_pid"
                            val result = graph.executions.runner.execute(UUID.randomUUID().toString(), RootCommand.wrap(listOf("/system/bin/sh", "-c", script), true, graph.root.su), dir,
                                closeInput = true, timeoutSeconds = duration + 60) { chunk ->
                                val count = minOf(chunk.length.toLong(), (16 * 1024 * 1024L - written).coerceAtLeast(0)).toInt()
                                writer.write(chunk, 0, count); writer.flush(); written += count; truncated += chunk.length - count
                            }
                            logFailure = "logcat 提前结束，exit=${result.exitCode}；${errors.readText()}"
                        }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) { logFailure = error.toString() }
                }
                logReady = withTimeoutOrNull(5000) { while (!ready.exists() && logger.isActive) delay(50); ready.exists() } == true
                if (!logReady) logFailure = logFailure ?: "logcat 未就绪：${errors.readText()}"
            }
            if (!run.stop.isCompleted) {
                startWall = System.currentTimeMillis(); startElapsed = SystemClock.elapsedRealtime()
                File(raw, "window-start").writeText(startWall.toString())
                report.getJSONObject("clocks").put("launchWallTime", startWall).put("launchElapsedRealtime", startElapsed)
                event(run, "launch_requested")
                store.write(run.row, report)
                if (root) monitor = launch(Dispatchers.IO) {
                    try { observeProcesses(run, raw) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) { monitorFailure = error.toString() }
                }
                if (run.target.uid / 100000 == android.os.Process.myUid() / 100000) withContext(Dispatchers.Main) { graph.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                else {
                    check(root) { "跨用户启动需要root" }
                    val (code, output) = command(dir, "am start --user ${run.target.uid / 100000} -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ${RootCommand.quote(run.target.packageName)}")
                    File(raw, "launch.txt").writeText(output); check(code == 0) { output }
                }
                withTimeoutOrNull(duration * 1000L) { run.stop.await() }
            }
            if (run.stop.isCompleted) state = "STOPPED"
        } catch (cancel: CancellationException) { state = "CANCELLED" }
        catch (error: Exception) { state = "ERROR"; report.getJSONArray("limitations").put(error.toString()); event(run, "collection_error", JSONObject().put("error", error.toString())) }
        finally { withContext(NonCancellable) {
            monitor?.cancelAndJoin(); logger?.cancelAndJoin()
            native?.let { collector -> collectSource(report, "native") { collector.finish(report); "native-events.jsonl；实际引擎状态见 nativeObservation" } }
            val endWall = System.currentTimeMillis()
            val endElapsed = SystemClock.elapsedRealtime()
            report.getJSONObject("clocks").put("endWallTime", endWall).put("endElapsedRealtime", endElapsed)
            val clockStable = startWall != null && kotlin.math.abs((endWall - startWall!!) - (endElapsed - startElapsed!!)) < 2000
            if (startWall != null && !clockStable) report.getJSONArray("limitations").put("采集期间墙钟变化，停止按时间窗口归属 tombstone 和历史退出记录；原始证据已保留。")
            source(report, "logcat", if (!root) "UNAVAILABLE" else if (logFailure != null) "ERROR" else if (truncated > 0) "TRUNCATED" else "COLLECTED",
                if (!root) "未获得 root；跨 UID 系统日志未采集" else "raw/logcat-window.txt；ready=$logReady；discardedCharacters=$truncated；${logFailure.orEmpty()}")
            source(report, "proc", if (!root) "UNAVAILABLE" else if (monitorFailure != null) "ERROR" else "COLLECTED", monitorFailure ?: "已观察身份见 processes；未出现的短命进程可能无法读取 maps")
            if (root && startWall != null) {
                collectSource(report, "exit-info") {
                    val (code, output) = command(dir, "dumpsys activity exit-info ${RootCommand.quote(run.target.packageName)}")
                    File(raw, "exit-info.txt").writeText(output)
                    require(code == 0 && output.contains("ACTIVITY MANAGER PROCESS EXIT INFO")) { "系统查询入口不支持或拒绝访问，见 raw/exit-info.txt" }
                    if (clockStable) report.put("exits", DiagnosticEvidence.exits(output, run.target, startWall!!, endWall, ZoneId.of(report.getJSONObject("clocks").getString("zoneId"))))
                    "raw/exit-info.txt；归属记录 ${report.getJSONArray("exits").length()} 条"
                }
                collectSource(report, "tombstones") { collectTombstones(run, raw, startWall!!, endWall, clockStable) }
            } else {
                source(report, "exit-info", "UNAVAILABLE", "${if (!root) "未获得 root" else "未启动目标"}；未调用跨 UID 的普通 ActivityManager 接口")
                source(report, "tombstones", "UNAVAILABLE", "${if (!root) "未获得 root" else "未启动目标"}；可导入目标 tombstone")
            }
            if (configured) collectSource(report, "configuration_cleanup") { if (zygisk) graph.nativeHost.call("clear-diagnostic", run.target.uid.toString(), run.target.packageName, run.row.id); if (javaConfigured) graph.framework.clearDiagnostic(key, run.row.id); "已移除本次早期配置；保存的插件启用状态未变" }
            // Import only this run's module events. The unfiltered system window stays a local attachment.
            val log = File(raw, "logcat-window.txt")
            var modeObserved = false
            if (log.exists()) log.useLines { lines -> lines.forEachIndexed { index, line ->
                if (line.contains("EasylspNative: ")) {
                    val payload = line.substringAfter("EasylspNative: ")
                    val parsed = runCatching { JSONObject(payload) }.getOrNull()
                    if (parsed != null && parsed.optInt("uid") == run.target.uid) {
                        File(dir, "native-plugin-events.jsonl").appendText(parsed.put("source", "raw/logcat-window.txt").put("line", index + 1).toString() + "\n")
                    }
                }
                if (line.contains("EasylspDiag") && line.contains(run.row.id)) {
                    val payload = line.substringAfter("EasylspDiag: ", "")
                    if (payload.startsWith("{")) {
                        val parsed = runCatching { JSONObject(payload) }.getOrNull()
                        if (parsed?.optString("diagnosticRunId") == run.row.id) {
                            if (parsed.optString("phase") == "module_loaded" && parsed.optString("mode") == run.row.mode) modeObserved = true
                            store.event(run.row, parsed.put("source", "raw/logcat-window.txt").put("line", index + 1))
                        }
                    }
                }
            } }
            graph.nativeInstances.importDiagnostic(run.row.projectId, run.row.id, report)
            report.put("modeVerification", if (!configured) "NOT_REQUESTED" else if (modeObserved) "OBSERVED" else "UNCONFIRMED")
            if (configured && !modeObserved) report.getJSONArray("limitations").put("已发布对照配置，但未取得目标读取配置的 module_loaded 事件；不能据此认定入口模式实际生效。")
            val eventFile = File(dir, "events.jsonl")
            if (eventFile.exists()) eventFile.useLines { lines -> lines.forEach { line ->
                val item = JSONObject(line)
                if (item.optString("phase") == "session_connected" || item.optString("phase") == "session_registered") report.getJSONArray("sessions").put(item)
            } }
            val observed = report.getJSONArray("processes")
            val initialization = mutableListOf<JSONObject>()
            if (eventFile.exists()) eventFile.useLines { lines -> lines.forEach { line ->
                val item = JSONObject(line)
                if (item.optString("phase") in setOf("module_loaded", "java_hook_installed", "session_connected", "session_registered")) initialization.add(item)
            } }
            initialization.addAll(graph.nativeInstances.events(run.row.projectId).filter { it.optString("diagnosticRunId") == run.row.id && it.optString("phase") in setOf("plugin_loading", "plugin_initialized", "plugin_init_failed") })
            for (i in 0 until observed.length()) {
                val process = observed.getJSONObject(i)
                val pid = process.getInt("pid")
                process.put("observed", true).put("initializationEvidence", JSONArray(initialization.filter {
                    it.optInt("pid") == pid && (!it.has("startTicks") || it.optLong("startTicks") == process.getLong("startTicks"))
                }.map { JSONObject(it.toString()).put("identityMatch", if (it.has("startTicks")) "PID_START_TICKS" else "PID_IN_RUN_WINDOW") }))
                process.put("exitEvidence", JSONArray((0 until report.getJSONArray("exits").length()).map { report.getJSONArray("exits").getJSONObject(it) }.filter { it.optInt("pid") == pid && it.optString("processName") == process.getString("processName") && it.optLong("timestamp") >= process.getLong("observedWallTime") }.map { JSONObject(it.toString()).put("identityMatch", "PID_NAME_TIME_WITHOUT_START_TICKS") }))
                process.put("aliveAtEnd", JSONObject.NULL)
                if (root) {
                    val (code, stat) = command(raw, "cat /proc/" + pid + "/stat")
                    if (code == 0) process.put("aliveAtEnd", stat.substringAfterLast(") ").split(' ')[19].toLong() == process.getLong("startTicks"))
                    process.put("livenessCheckedAt", System.currentTimeMillis()).put("livenessSource", "/proc/PID/stat")
                }
            }
            report.put("collectionComplete", state == "COMPLETE" && root && monitorFailure == null && logFailure == null)
            report.put("observation", DiagnosticEvidence.observation(observed, report.getBoolean("collectionComplete")))
            report.put("conclusion", DiagnosticEvidence.conclusion(report.getJSONArray("tombstones"), report.getJSONArray("exits")))
            event(run, "collection_finished", JSONObject().put("state", state))
            run.row = run.row.copy(state = state, finishedAt = endWall)
            try { store.write(run.row, report) }
            finally { active.remove(run.row.id); run.ended.complete(Unit); if (active.isEmpty()) DiagnosticService.finish(graph) }
        } }
        if (state == "ERROR") 1 else 0
    }
    private suspend fun collectSource(report: JSONObject, name: String, work: suspend () -> String) {
        try { source(report, name, "COLLECTED", work()) }
        catch (error: Exception) { source(report, name, "UNAVAILABLE", error.toString()) }
    }
    private suspend fun observeProcesses(run: Active, raw: File) {
        val seen = mutableSetOf<String>()
        while (currentCoroutineContext().isActive) {
            val (code, listing) = command(raw, "ps -A -o PID,UID,NAME")
            require(code == 0) { listing }
            for (line in listing.lineSequence().drop(1)) {
                val fields = line.trim().split(Regex("\\s+"))
                if (fields.size != 3 || fields[1].toIntOrNull() != run.target.uid || !run.target.matches(fields[2])) continue
                val pid = fields[0].toInt()
                val (statCode, stat) = command(raw, "cat /proc/$pid/stat")
                if (statCode != 0) { event(run, "proc_unavailable", JSONObject().put("pid", pid).put("detail", stat)); continue }
                val ticks = stat.substringAfterLast(") ").split(' ')[19].toLong()
                if (!seen.add("$pid:$ticks")) continue
                val identity = JSONObject().put("uid", run.target.uid).put("pid", pid).put("processName", fields[2]).put("startTicks", ticks)
                    .put("clockTicksPerSecond", android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK))
                    .put("diagnosticRunId", run.row.id).put("observedWallTime", System.currentTimeMillis()).put("observedElapsedRealtime", SystemClock.elapsedRealtime())
                val (mapsCode, maps) = command(raw, "cat /proc/$pid/maps")
                val (afterCode, after) = command(raw, "cat /proc/$pid/stat")
                val same = afterCode == 0 && after.substringAfterLast(") ").split(' ')[19].toLong() == ticks
                if (mapsCode == 0 && same) {
                    val name = "maps-$pid-$ticks.txt"; File(raw, name).writeText(maps); identity.put("maps", "raw/$name")
                } else identity.put("mapsUnavailable", "读取时进程已退出、身份变化或访问被拒绝")
                run.report.getJSONArray("processes").put(identity)
                event(run, "process_observed", JSONObject(identity.toString()))
            }
            delay(400)
        }
    }
    private suspend fun collectTombstones(run: Active, raw: File, start: Long, end: Long, clockStable: Boolean): String {
        val (code, listing) = command(raw, "find /data/tombstones -maxdepth 1 -type f -name 'tombstone_*' ! -name '*.pb' -newer ${RootCommand.quote(File(raw, "window-start").path)}")
        require(code == 0) { listing }
        var count = 0
        for (path in listing.lines().filter { it.startsWith("/data/tombstones/tombstone_") }) {
            val (readCode, text) = command(raw, "cat ${RootCommand.quote(path)}")
            require(readCode == 0) { "无法读取 $path：$text" }
            val name = File(path).name
            val parsed = DiagnosticEvidence.tombstone(text, "raw/$name") ?: continue
            if (!clockStable || !DiagnosticEvidence.belongs(parsed, run.target, start, end)) continue
            File(raw, name).writeText(text)
            run.report.getJSONArray("tombstones").put(parsed); count++
            val proto = File(raw, "$name.pb").apply { writeBytes(byteArrayOf()) }
            val (protoCode, detail) = command(raw, "cat ${RootCommand.quote("$path.pb")} > ${RootCommand.quote(proto.path)}")
            if (protoCode == 0) parsed.put("protobuf", "raw/${proto.name}")
            else { proto.delete(); parsed.put("protobufUnavailable", detail) }
        }
        return "按进程、UID 和内容时间归属 $count 个 tombstone；未取最新文件代替归属"
    }
}
