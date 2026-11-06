package io.github.easylsp.app.feature.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.easylsp.app.data.PluginEntity
import org.json.JSONObject

/** Host-owned form controls; no plugin HTML or code executes on this screen. */
@Composable fun PluginConfigurationFields(plugin: PluginEntity, save: (String) -> Unit) {
    if (plugin.configSchema == "{}") return
    val schema = remember(plugin.configSchema) { JSONObject(plugin.configSchema).getJSONObject("properties") }
    var values by remember(plugin.id, plugin.configJson) { mutableStateOf(JSONObject(plugin.configJson)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("插件配置", style = MaterialTheme.typography.titleMedium)
        schema.keys().forEach { key ->
            val field = schema.getJSONObject(key)
            val title = field.optString("title", key)
            if (field.getString("type") == "boolean") Row {
                Text(title, Modifier.weight(1f))
                Switch(values.optBoolean(key), { value -> values = JSONObject(values.toString()).put(key, value) })
            } else {
                var text by remember(plugin.id, plugin.configJson, key) { mutableStateOf(values.opt(key)?.toString().orEmpty()) }
                val choices = field.optJSONArray("enum")
                if (choices != null) Row {
                    for (i in 0 until choices.length()) {
                        val value = choices.get(i)
                        FilterChip(values.opt(key) == value, { text = value.toString(); values = JSONObject(values.toString()).put(key, value) }, label = { Text(value.toString()) })
                    }
                } else OutlinedTextField(text, { next ->
                    text = next
                    val value = when (field.getString("type")) {
                        "integer" -> next.toLongOrNull()
                        "number" -> next.toDoubleOrNull()?.takeIf { it.isFinite() }
                        else -> next
                    }
                    values = JSONObject(values.toString()).put(key, value ?: JSONObject.NULL)
                }, label = { Text(title) })
            }
        }
        TextButton(onClick = { save(values.toString()) }) { Text("保存配置（下次发布生效）") }
    }
}
