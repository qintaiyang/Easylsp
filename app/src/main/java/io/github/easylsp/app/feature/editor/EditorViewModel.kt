package io.github.easylsp.app.feature.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.data.PluginEntity
import io.github.easylsp.app.data.PluginPaths
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val store = (application as EasylspApp).store
    data class Document(val files: Map<String, String>, val name: String, val author: String, val version: String, val description: String, val process: String, val active: String = "main.java", val opened: List<String> = listOf("main.java"))
    val document = MutableStateFlow<Document?>(null)
    val dirty = MutableStateFlow(false)
    private var entry = "main.java"
    private var native = false
    private var baseRevision = 0L
    private var original: Document? = null
    private var pluginId: String? = null
    private data class Write(val id: String, val draft: Document?, val completed: CompletableDeferred<Unit>? = null)
    private val writes = Channel<Write>(Channel.UNLIMITED)
    init { viewModelScope.launch(Dispatchers.IO) { for ((id, draft, completed) in writes) { if (draft == null) store.draftFile(id).delete() else store.draftFile(id).writeText(encode(draft).toString()); completed?.complete(Unit) } } }
    fun load(plugin: PluginEntity) {
        if (pluginId == plugin.id) return
        pluginId = plugin.id
        entry = plugin.entry
        native = plugin.runtime == "NATIVE"
        baseRevision = plugin.revision
        viewModelScope.launch(Dispatchers.IO) {
            val base = Document(store.readFiles(plugin), plugin.name, plugin.author, plugin.version, plugin.description, plugin.processName, active = entry, opened = listOf(entry))
            original = base
            val file = store.draftFile(plugin.id)
            document.value = if (file.exists()) decode(JSONObject(file.readText())) else base
            updateDirty()
        }
    }
    fun change(transform: (Document) -> Document) {
        val next = transform(requireNotNull(document.value))
        document.value = next; updateDirty(); writes.trySend(Write(requireNotNull(pluginId), next))
    }
    private fun updateDirty() {
        val d = document.value; val base = original
        dirty.value = d != null && base != null && d.copy(active = base.active, opened = base.opened) != base
    }
    fun open(path: String) = change { it.copy(active = path, opened = (it.opened + path).distinct()) }
    fun close(path: String) = change { val tabs = it.opened - path; it.copy(opened = tabs.ifEmpty { listOf(entry) }, active = if (it.active == path) tabs.lastOrNull() ?: entry else it.active) }
    fun add(path: String, folder: Boolean) {
        val key = path.trim().removeSuffix("/") + if (folder) "/" else ""
        PluginPaths.validate(key)
        if (native) require(key.startsWith("src/") || key.startsWith("include/")) { "C 文件放在 src/ 或 include/" }
        val d = requireNotNull(document.value)
        require(key != entry && key !in d.files && key.removeSuffix("/") !in d.files && "$key/" !in d.files) { "文件已存在" }
        require(key.split('/').dropLast(1).runningFold("") { parent, name -> if (parent.isEmpty()) name else "$parent/$name" }.drop(1).none { it in d.files }) { "父路径不是文件夹" }
        change { it.copy(files = it.files + (key to "")) }
        if (!folder) open(key)
    }
    fun addComponent(id: String) {
        require(!native && id.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*"))) { "组件名须以字母开头，仅含字母、数字、点、横线、下划线" }
        val d = requireNotNull(document.value)
        val path = "src/$id.bsh"
        val manifest = d.files["plugin.json"]?.let { JSONObject(it) } ?: JSONObject().put("schema", 1)
        val components = manifest.optJSONArray("components") ?: org.json.JSONArray()
        require(path !in d.files && (0 until components.length()).none { components.getJSONObject(it).getString("id") == id }) { "组件已存在" }
        components.put(JSONObject().put("id", id).put("path", path).put("requires", org.json.JSONArray()))
        manifest.put("components", components)
        val source = listOf("void onLoad() { }", "void onUnload() { }", "return this;").joinToString(10.toChar().toString())
        change { it.copy(files = it.files + mapOf("plugin.json" to manifest.toString(2), path to source)) }
        open(path)
    }
    fun remove(path: String) {
        require(path != entry && !(path.endsWith('/') && entry.startsWith(path))) { "不能删除入口文件或其父目录" }
        change { d ->
            val files = d.files.filterKeys { it != path && !(path.endsWith('/') && it.startsWith(path)) }
            d.copy(files = files, opened = d.opened.filter { it in files }, active = if (d.active in files) d.active else entry)
        }
    }
    fun rename(path: String, target: String) {
        require(path != entry && !(path.endsWith('/') && entry.startsWith(path))) { "不能重命名入口文件或其父目录" }
        val d = requireNotNull(document.value)
        val next = target.trim().removeSuffix("/") + if (path.endsWith('/')) "/" else ""
        PluginPaths.validate(next)
        if (native) require(next.startsWith("src/") || next.startsWith("include/")) { "C 文件放在 src/ 或 include/" }
        require(next != path && next !in d.files && next.removeSuffix("/") !in d.files && "$next/" !in d.files && !(path.endsWith('/') && next.startsWith(path))) { "目标路径已存在或位于原文件夹内" }
        fun moved(key: String) = if (key == path || (path.endsWith('/') && key.startsWith(path))) next + key.removePrefix(path) else key
        val files = d.files.mapKeys { (key, _) -> moved(key) }
        require(files.size == d.files.size && files.keys.none { key -> files.keys.any { other -> other != key && !other.endsWith('/') && key.startsWith("$other/") } }) { "目标路径与现有文件冲突" }
        change { it.copy(files = files, active = moved(it.active), opened = it.opened.map(::moved)) }
    }
    suspend fun save(plugin: PluginEntity) {
        val d = requireNotNull(document.value)
        store.save(plugin.id, d.name, d.author, d.description, d.version, d.files.getValue(entry), d.process, d.files, baseRevision)
        baseRevision++
        original = d; updateDirty(); persist(document.value.takeUnless { it == d })
    }
    suspend fun keepDraft() { persist(document.value) }
    suspend fun discard() { document.value = original; dirty.value = false; persist(null) }
    private suspend fun persist(draft: Document?) {
        val completed = CompletableDeferred<Unit>()
        writes.send(Write(requireNotNull(pluginId), draft, completed)); completed.await()
    }
    private fun encode(d: Document) = JSONObject().put("files", JSONObject(d.files)).put("name", d.name).put("author", d.author).put("version", d.version).put("description", d.description).put("process", d.process).put("active", d.active).put("opened", org.json.JSONArray(d.opened))
    private fun decode(o: JSONObject): Document {
        val files = o.getJSONObject("files")
        val opened = o.getJSONArray("opened")
        return Document(files.keys().asSequence().associateWith { files.getString(it) }, o.getString("name"), o.getString("author"), o.getString("version"), o.getString("description"), o.getString("process"), o.getString("active"), (0 until opened.length()).map { opened.getString(it) })
    }
}
