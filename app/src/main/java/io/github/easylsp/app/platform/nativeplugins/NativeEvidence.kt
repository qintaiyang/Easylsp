package io.github.easylsp.app.platform.nativeplugins

import org.json.JSONArray
import org.json.JSONObject

internal fun nativeEventKey(row: JSONObject) = "${row.getInt("uid")}/${row.getInt("pid")}/${row.getLong("startTicks")}/${row.getString("instanceId")}/${row.getLong("sequence")}"

/** One process event may arrive through Binder, logcat and saved diagnostics. */
internal fun mergeNativeEvidence(existing: List<JSONObject>, incoming: List<JSONObject>): List<JSONObject> {
    val rows = linkedMapOf<String, JSONObject>()
    for (event in existing + incoming) {
        val key = nativeEventKey(event)
        val previous = rows[key]
        val sources = mutableListOf<JSONObject>()
        for (row in listOfNotNull(previous, event)) {
            val saved = row.optJSONArray("sources")
            if (saved != null) for (i in 0 until saved.length()) sources += saved.getJSONObject(i)
            else sources += JSONObject().apply {
                for (field in listOf("source", "line", "diagnosticRunId", "diagnosticFile", "diagnosticLine", "sessionId")) if (row.has(field)) put(field, row.get(field))
            }
        }
        rows[key] = JSONObject(event.toString()).put("sources", JSONArray(sources.distinctBy { it.toString() }))
    }
    return rows.values.toList()
}
internal fun nativeLifecycle(events: List<JSONObject>): JSONObject? = events.filter {
    it.getString("phase") in setOf("plugin_loading", "plugin_initialized", "plugin_init_failed", "plugin_load_failed")
}.maxByOrNull { it.getLong("sequence") }
