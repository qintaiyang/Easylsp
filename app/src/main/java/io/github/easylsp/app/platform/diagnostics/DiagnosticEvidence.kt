package io.github.easylsp.app.platform.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class DiagnosticMode(val label: String) {
    EXTERNAL_ONLY("仅进程外采集"), ENTRY_ONLY("仅模块入口"), NATIVE_OBSERVE("早期 native 观测"), JAVA_NO_PLUGINS("Java 初始化，抑制自动加载"), NORMAL("正常加载（含已启用插件）")
}

data class DiagnosticTarget(val packageName: String, val uid: Int, val processName: String) {
    init {
        require(processName == "*" || processName == packageName || processName.startsWith("$packageName:")) { "进程必须属于当前包，或使用 * 采集全部进程" }
    }
    fun matches(name: String) = if (processName == "*") name == packageName || name.startsWith("$packageName:") else name == processName
}

/** Parsers preserve source addresses and line references. They never infer anti-hook detection. */
object DiagnosticEvidence {
    private val dumpDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    fun tombstone(text: String, source: String): JSONObject? {
        // Android appends other threads after a dashed separator. Those are not the crashing frame/register set.
        val lines = text.lineSequence().takeWhile { !it.startsWith("--- ---") }.toList()
        val identity = Regex("pid: (\\d+), tid: (\\d+), name: .*?>>> (.*?) <<<").find(text) ?: return null
        val stamp = Regex("(?m)^Timestamp: (.+)$").find(text)?.groupValues?.get(1)?.trim()
        val time = stamp?.let {
            runCatching { OffsetDateTime.parse(it.replace(' ', 'T').replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2")).toInstant().toEpochMilli() }.getOrNull()
        }
        val signal = Regex("signal (\\d+) \\(([^)]+)\\), code (-?\\d+) \\(([^)]+)\\), fault addr (\\S+)").find(text)
        val registers = JSONObject()
        val frames = JSONArray()
        lines.forEachIndexed { index, line ->
            if (Regex("^\\s*(x\\d{1,2}|lr|sp|pc|pstate|pst)\\s").containsMatchIn(line))
                Regex("\\b(x\\d{1,2}|lr|sp|pc|pstate|pst)\\s+([0-9a-fA-F]{8,16})\\b").findAll(line).forEach { registers.put(it.groupValues[1], "0x" + it.groupValues[2]) }
            val frame = Regex("^\\s*#(\\d+)\\s+pc\\s+([0-9a-fA-F]+)\\s+(\\S+)(.*)$").find(line)
            if (frame != null) frames.put(JSONObject().put("index", frame.groupValues[1].toInt()).put("pc", "0x" + frame.groupValues[2])
                .put("addressKind", "TOMBSTONE_RELATIVE_PC").put("module", frame.groupValues[3]).put("detail", frame.groupValues[4].trim())
                .put("buildId", Regex("BuildId: ([0-9a-fA-F]+)").find(line)?.groupValues?.get(1) ?: JSONObject.NULL)
                .put("source", source).put("line", index + 1))
        }
        return JSONObject().put("source", source).put("timestamp", time ?: JSONObject.NULL).put("timestampRaw", stamp ?: JSONObject.NULL)
            .put("pid", identity.groupValues[1].toInt()).put("tid", identity.groupValues[2].toInt()).put("processName", identity.groupValues[3])
            .put("uid", Regex("(?m)^uid: (\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: JSONObject.NULL)
            .put("classification", if (signal == null) "UNKNOWN" else "NATIVE_FATAL_SIGNAL")
            .put("signal", signal?.groupValues?.get(1)?.toInt() ?: JSONObject.NULL).put("signalName", signal?.groupValues?.get(2) ?: JSONObject.NULL)
            .put("code", signal?.groupValues?.get(3)?.toInt() ?: JSONObject.NULL).put("faultAddress", signal?.groupValues?.get(5) ?: JSONObject.NULL)
            .put("registers", registers).put("frames", frames).put("moduleMatch", "UNCONFIRMED")
    }

    fun belongs(tombstone: JSONObject, target: DiagnosticTarget, start: Long, end: Long): Boolean =
        !tombstone.isNull("timestamp") && !tombstone.isNull("uid") && tombstone.getInt("uid") == target.uid &&
            target.matches(tombstone.getString("processName")) && tombstone.getLong("timestamp") in start..end

    fun exits(text: String, target: DiagnosticTarget, start: Long, end: Long, zone: ZoneId): JSONArray {
        val result = JSONArray()
        val entries = Regex("(?m)^\\s*ApplicationExitInfo #\\d+:").findAll(text).toList()
        for ((index, entry) in entries.withIndex()) {
            val block = text.substring(entry.range.first, entries.getOrNull(index + 1)?.range?.first ?: text.length)
            val header = Regex("timestamp=(\\S+ \\S+) pid=(\\d+) realUid=(\\d+)").find(block) ?: continue
            val time = runCatching { LocalDateTime.parse(header.groupValues[1], dumpDate).atZone(zone).toInstant().toEpochMilli() }.getOrNull() ?: continue
            val process = Regex("process=(\\S+) reason=(\\d+) \\((.*?)\\) subreason=.*?status=(-?\\d+)").find(block) ?: continue
            if (time !in start..end || header.groupValues[3].toInt() != target.uid || !target.matches(process.groupValues[1])) continue
            val reason = process.groupValues[2].toInt()
            val status = process.groupValues[4].toInt()
            val classification = when (reason) {
                1 -> "SELF_EXIT"
                2 -> if (status in setOf(4, 6, 7, 8, 11, 31)) "NATIVE_FATAL_SIGNAL" else "SIGNALLED"
                4 -> "JAVA_EXCEPTION"
                5 -> "NATIVE_FATAL_SIGNAL"
                6 -> "ANR"
                3, 9, 10, 11, 12, 13 -> "EXTERNAL_TERMINATION"
                else -> "UNKNOWN"
            }
            result.put(JSONObject().put("source", "raw/exit-info.txt").put("line", text.take(entry.range.first).count { it == '\n' } + 1)
                .put("timestamp", time).put("pid", header.groupValues[2].toInt()).put("uid", target.uid).put("processName", process.groupValues[1])
                .put("reason", reason).put("reasonDescription", process.groupValues[3]).put("status", status)
                .put("signal", if (reason == 2) status else JSONObject.NULL).put("classification", classification)
                .put("description", Regex("description=(.*)").find(block)?.groupValues?.get(1) ?: JSONObject.NULL))
        }
        return result
    }

    /** A disconnect alone is never an exit diagnosis. Multiple processes retain separate evidence. */
    fun conclusion(tombstones: JSONArray, exits: JSONArray): JSONObject {
        val evidence = (0 until tombstones.length()).map { tombstones.getJSONObject(it) } + (0 until exits.length()).map { exits.getJSONObject(it) }
        val kinds = evidence.map { it.getString("classification") }.distinct()
        return JSONObject().put("classification", when { kinds.isEmpty() -> "NO_EXIT_EVIDENCE"; kinds.size == 1 -> kinds.single(); else -> "MULTIPLE" })
            .put("evidence", JSONArray(evidence.map { JSONObject().put("source", it.getString("source")).put("pid", it.getInt("pid")) }))
            .put("note", if (evidence.isEmpty()) "没有取得可归属本次启动的退出证据；断连不等于崩溃。" else "分类来自系统记录；信号、环境查询或相邻时间不构成检测 Hook 的证明。")
    }

    fun observation(processes: JSONArray, complete: Boolean): JSONObject {
        val rows = (0 until processes.length()).map { processes.getJSONObject(it) }
        rows.forEach { row -> row.put("observationState", when {
            row.optBoolean("aliveAtEnd") -> "ALIVE_AT_END"
            (row.optJSONArray("exitEvidence")?.length() ?: 0) > 0 -> "EXIT_OBSERVED"
            else -> "OBSERVATION_INCOMPLETE"
        }) }
        return JSONObject().put("collectionComplete", complete).put("observedProcesses", rows.size)
            .put("aliveAtEnd", rows.count { it.optBoolean("aliveAtEnd") })
            .put("exitsObserved", rows.count { (it.optJSONArray("exitEvidence")?.length() ?: 0) > 0 })
            .put("state", when {
                !complete -> "OBSERVATION_INCOMPLETE"
                rows.isEmpty() -> "NOT_OBSERVED"
                rows.map { it.getString("observationState") }.distinct().size == 1 -> rows.first().getString("observationState")
                else -> "MIXED"
            })
    }

    fun markdown(report: JSONObject): String = buildString {
        appendLine("# 启动诊断 ${report.getString("diagnosticRunId")}")
        appendLine("\n${report.getString("packageName")} · 样本 r${report.getLong("sampleRevision")} · ${report.getString("mode")} · ${report.optString("state")}")
        appendLine("\n## 退出原因\n")
        val conclusion = report.getJSONObject("conclusion")
        appendLine(conclusion.getString("classification")); appendLine(conclusion.getString("note"))
        appendLine("\n## 进程观察\n")
        report.optJSONObject("observation")?.let { appendLine(it.getString("state")) }
        val processes = report.optJSONArray("processes") ?: JSONArray()
        if (processes.length() == 0) appendLine("未观察到可读取身份的进程；不能据此判定未启动或崩溃。")
        for (i in 0 until processes.length()) {
            val process = processes.getJSONObject(i)
            appendLine("- " + process.optString("processName") + " / PID " + process.optInt("pid") + " / startTicks " + process.optLong("startTicks") +
                "；已观察；初始化证据 " + (process.optJSONArray("initializationEvidence")?.length() ?: 0) +
                "；结束存活 " + process.opt("aliveAtEnd") + "；退出证据 " + (process.optJSONArray("exitEvidence")?.length() ?: 0))
        }
        appendLine("采集完整：" + report.optBoolean("collectionComplete") + "；无法读取 liveness 时保持未确认。")
        appendLine("\n## 崩溃位置\n")
        val tombstones = report.getJSONArray("tombstones")
        if (tombstones.length() == 0) appendLine("未取得本次目标的 tombstone，不能提供故障指令或调用栈。")
        for (i in 0 until tombstones.length()) {
            val tombstone = tombstones.getJSONObject(i)
            appendLine("- ${tombstone.getString("processName")} / PID ${tombstone.getInt("pid")} / ${tombstone.opt("signalName")} — ${tombstone.getString("source")}")
            val frames = tombstone.getJSONArray("frames")
            for (j in 0 until minOf(frames.length(), 12)) {
                val frame = frames.getJSONObject(j)
                appendLine("  - ${frame.getString("module")} + ${frame.getString("pc")}（tombstone 相对 PC；样本匹配见 modules.json）[${frame.getString("source")}:${frame.getInt("line")}] ${frame.getString("detail")}")
            }
        }
        appendLine("\n## 退出记录\n")
        val exits = report.getJSONArray("exits")
        for (i in 0 until exits.length()) {
            val exit = exits.getJSONObject(i)
            appendLine("- ${exit.getString("processName")} / PID ${exit.getInt("pid")}：${exit.getString("classification")}，${exit.getString("reasonDescription")}，status=${exit.getInt("status")} [${exit.getString("source")}:${exit.getInt("line")}]")
        }
        report.optJSONObject("nativeObservation")?.let { native ->
            appendLine("\n## Native 观测\n")
            appendLine("${report.optString("nativeEngine")} · ${native.getString("backend")} · ${native.getInt("events")} 事件 · 已报告丢弃 ${native.getLong("reportedDrops")}")
            val calls = native.getJSONArray("exitCalls")
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                appendLine("- PID ${call.getInt("pid")} / startTicks ${call.getLong("startTicks")} 调用 ${call.getString("name")}(${call.getLong("argument0")}, ${call.getLong("argument1")}, ${call.getLong("argument2")})，返回地址 ${call.getString("callerReturnAddress")}，${call.optString("callerModule", "未关联模块")} + ${call.optString("callerElfVaddr", "未知")} [${call.getString("source")}:${call.getInt("line")}]")
            }
            if (!native.isNull("collectorError")) appendLine("接收器错误：${native.getString("collectorError")}")
            if (native.optString("collectorStderr").isNotEmpty()) appendLine("接收器输出：${native.getString("collectorStderr")}")
            appendLine("探针安装结果见 report.json；构造/卸载时间线见 native-events.jsonl。调用参数为观察事实，不自动判定检测目的。")
        }
        appendLine("\n## 证据与限制\n")
        val sources = report.getJSONArray("sources")
        for (i in 0 until sources.length()) {
            val source = sources.getJSONObject(i)
            appendLine("- ${source.getString("name")}：${source.getString("status")}；${source.optString("detail")}")
        }
        val limits = report.getJSONArray("limitations")
        for (i in 0 until limits.length()) appendLine("- ${limits.getString(i)}")
        appendLine("\n启用插件与 revision、时钟、进程身份及原始来源见 report.json；阶段与断连事件见 events.jsonl。")
    }
}
