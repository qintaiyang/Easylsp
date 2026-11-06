package io.github.easylsp.app.data

import org.json.JSONObject

/** Exact release pins: no implicit latest, source fallback, or version guessing. */
object ReleaseDependencies {
    data class Identity(val sourceId: String, val publisherId: String, val catalogPluginId: String)
    data class Pin(val identity: Identity, val releaseId: String)
    fun identity(manifest: JSONObject, sourceId: String) = Identity(sourceId, manifest.getString("publisherId"), manifest.getString("catalogPluginId"))
    fun pins(manifest: JSONObject, sourceId: String): List<Pin> {
        val deps = manifest.optJSONArray("dependencies") ?: return emptyList()
        return (0 until deps.length()).map { i -> deps.getJSONObject(i).let { Pin(identity(it, it.optString("sourceId", sourceId)), it.getString("releaseId")) } }
    }
    fun resolve(root: Pair<String, JSONObject>, lookup: (Pin) -> JSONObject): List<Pair<String, JSONObject>> {
        val versions = mutableMapOf<Identity, String>()
        val visiting = mutableSetOf<Identity>(); val done = mutableSetOf<Identity>()
        val result = mutableListOf<Pair<String, JSONObject>>()
        fun visit(source: String, manifest: JSONObject) {
            val id = identity(manifest, source); val version = manifest.getString("releaseId")
            val previous = versions.putIfAbsent(id, version)
            require(previous == null || previous == version) { "依赖版本冲突：${id.catalogPluginId}，$previous / $version" }
            require(id !in visiting) { "依赖存在循环：${id.catalogPluginId}" }
            if (id in done) return
            require(versions.size <= 64) { "依赖图超过64个发行" }
            visiting.add(id)
            for (pin in pins(manifest, source)) {
                val child = lookup(pin)
                require(identity(child, pin.identity.sourceId) == pin.identity && child.getString("releaseId") == pin.releaseId) { "依赖返回身份不匹配" }
                visit(pin.identity.sourceId, child)
            }
            visiting.remove(id); done.add(id); result.add(source to manifest)
        }
        visit(root.first, root.second); return result
    }
    fun installedOrder(plugin: PluginEntity, installed: List<PluginEntity>): List<PluginEntity> {
        if (plugin.releaseId == null) return listOf(plugin)
        val root = JSONObject(plugin.releaseManifest)
        val manifests = resolve(plugin.marketSourceId to root) { pin ->
            val row = requireNotNull(installed.find { it.marketSourceId == pin.identity.sourceId && it.publisherId == pin.identity.publisherId && it.catalogPluginId == pin.identity.catalogPluginId && it.releaseId == pin.releaseId }) { "尚未安装固定依赖：${pin.identity.catalogPluginId}/${pin.releaseId}" }
            JSONObject(row.releaseManifest)
        }
        return manifests.flatMap { (source, manifest) ->
            val release = PluginRelease(manifest)
            release.runtimes.map { runtime -> requireNotNull(installed.find { it.marketSourceId == source && it.publisherId == release.publisherId && it.catalogPluginId == release.catalogPluginId && it.releaseId == release.releaseId && it.runtime == runtime }) { "联合发行组件缺失：${release.name}/$runtime" } }
        }
    }
    fun components(plugin: PluginEntity, installed: List<PluginEntity>): List<PluginEntity> =
        if (plugin.releaseId == null) listOf(plugin) else installed.filter {
            it.marketSourceId == plugin.marketSourceId && it.publisherId == plugin.publisherId &&
                it.catalogPluginId == plugin.catalogPluginId && it.releaseId == plugin.releaseId
        }
    fun requireCanDisable(plugin: PluginEntity, installed: List<PluginEntity>) = requireCanDisableSet(listOf(plugin), installed)
    fun requireCanDisableSet(removed: List<PluginEntity>, installed: List<PluginEntity>) {
        val ids = removed.map { it.id }.toSet()
        val dependents = installed.filter { it.desiredEnabled && it.id !in ids && it.releaseId != null }.filter { row ->
            removed.any { plugin -> plugin.releaseId != null && (
                components(plugin, installed).any { it.id == row.id } ||
                pins(JSONObject(row.releaseManifest), row.marketSourceId).any {
                    it.identity == Identity(plugin.marketSourceId, plugin.publisherId!!, plugin.catalogPluginId!!) && it.releaseId == plugin.releaseId
                })
            }
        }
        require(dependents.isEmpty()) { "请先整组停用此发行或依赖此发行的插件：${dependents.joinToString { it.name }}" }
    }
}
