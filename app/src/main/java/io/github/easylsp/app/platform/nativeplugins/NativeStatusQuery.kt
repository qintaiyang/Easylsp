package io.github.easylsp.app.platform.nativeplugins

import org.json.JSONArray
import org.json.JSONObject

/** Current evidence must match an observed process lifetime, not just a reused PID. */
internal object NativeStatusQuery {
    fun page(events: List<JSONObject>, processes: List<JSONObject>, args: JSONObject): JSONObject {
        val scope = args.optString("scope", "current")
        require(scope in setOf("current", "history")) { "scope 必须为 current 或 history" }
        val cursor = args.optInt("cursor", 0); val limit = args.optInt("limit", 10)
        require(cursor >= 0 && limit in 1..50) { "cursor 必须 >= 0；limit 必须在 1..50 范围内" }
        val all = events.groupBy { listOf(it.getInt("uid"), it.getInt("pid"), it.getLong("startTicks"), it.getString("instanceId")) }.values.toList()
        fun process(row: JSONObject) = processes.firstOrNull {
            it.getInt("uid") == row.getInt("uid") && it.getInt("pid") == row.getInt("pid") && it.optLong("startTicks", -1) == row.getLong("startTicks")
        }
        val matches = all.filter { rows ->
            val row = rows.last(); val live = process(row)
            (scope == "history" || live != null) &&
                (!args.has("instanceId") || row.getString("instanceId") == args.getString("instanceId")) &&
                (!args.has("processName") || (live?.optString("processName") ?: row.optString("processName")) == args.getString("processName"))
        }.asReversed()
        val page = matches.drop(cursor).take(limit).map { rows ->
            val latest = rows.maxBy { it.getLong("sequence") }
            val hooks = rows.filter { it.getInt("hookId") != 0 }.groupBy { it.getInt("hookId") }
            val live = process(latest)
            JSONObject().apply {
                for (key in listOf("instanceId", "uid", "pid", "startTicks", "buildId", "generation", "sourceRevision", "dropped")) put(key, latest.get(key))
            }.put("processName", live?.opt("processName") ?: latest.opt("processName") ?: JSONObject.NULL)
                .put("lifecycle", nativeLifecycle(rows)?.getString("phase") ?: "UNKNOWN")
                .put("liveness", if (live != null) "ALIVE_AT_QUERY" else "UNCONFIRMED")
                .put("hookCount", hooks.size).put("hooksWithHitEvidence", hooks.values.count { changes -> changes.any { it.getString("phase") == "hook_hit" } })
                .put("eventCount", rows.size).put("sourceCount", rows.sumOf { it.optJSONArray("sources")?.length() ?: 0 })
                .put("evidenceRead", JSONObject().put("tool", "read_plugin_logs").put("arguments", JSONObject()
                    .put("pluginId", latest.getString("pluginId")).put("instanceId", latest.getString("instanceId")).put("offset", 0).put("limit", 20)))
        }
        return JSONObject().put("scope", scope).put("instances", JSONArray(page)).put("matchingInstances", matches.size)
            .put("recordedInstances", all.size).put("nextCursor", if (cursor + page.size < matches.size) cursor + page.size else JSONObject.NULL)
    }
}
