package io.github.easylsp.app.feature.plugins

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.easylsp.app.MainViewModel
import io.github.easylsp.app.data.PluginEntity
import io.github.easylsp.app.data.ProjectEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

@Composable fun PluginReleaseActions(vm: MainViewModel, project: ProjectEntity, plugin: PluginEntity) {
    val builds by vm.nativeBuilds.collectAsStateWithLifecycle()
    var cached by remember(plugin.id) { mutableStateOf<List<io.github.easylsp.app.data.PluginRelease>>(emptyList()) }
    var showCache by remember { mutableStateOf(false) }
    var migrate by remember { mutableStateOf<io.github.easylsp.app.data.PluginRelease?>(null) }
    if (plugin.source in setOf("IMPORTED_RELEASE", "MARKET")) {
        Text("${if (plugin.source == "MARKET") "市场签名发行" else "本地接收发行（未验证市场签名）"} v${plugin.marketVersionCode} · ${plugin.releaseId}", style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = { vm.enableRelease(plugin) }) { Text("启用固定发行与依赖（重启生效）") }
        TextButton(onClick = { vm.disableRelease(plugin) }) { Text("整组停用发行") }
        TextButton(onClick = { vm.disableRelease(plugin, delete = true) }) { Text("整组停用并删除发行") }
        TextButton(onClick = { vm.action { cached = vm.graph.store.cachedReleases(plugin.id); showCache = true; "已读取本地发行缓存" } }) { Text("缓存版本与回退") }
        if (plugin.localModified) TextButton(onClick = { vm.action { vm.graph.store.preserveLocalCopy(plugin.id); "修改已保留为停用的本地副本，原插件恢复固定发行；已有发布未改变" } }) { Text("保留修改为本地副本") }
    }
    if (showCache) AlertDialog(onDismissRequest = { showCache = false }, title = { Text("选择缓存发行") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            Text("切换只更新本地安装记录。Native 仍需显式发布所选构建，再重启目标；Java 需要重新加载。")
            cached.forEach { release -> TextButton(enabled = !plugin.localModified && release.releaseId != plugin.releaseId, onClick = { vm.action {
                val schemaFile = File(vm.graph.store.releaseCache(release.catalogPluginId, release.releaseId, plugin.marketSourceId), "config.schema.json")
                val nextSchema = withContext(Dispatchers.IO) { if (schemaFile.isFile) schemaFile.readText() else "{}" }
                if (nextSchema != plugin.configSchema) { migrate = release; showCache = false; "新旧配置结构不同，请确认新版配置后切换" }
                else { vm.graph.store.installCachedRelease(project.id, release.catalogPluginId, release.releaseId, sourceId = plugin.marketSourceId); showCache = false; "缓存发行已切换，已有运行进程未改变" }
            } }) { Text("${release.versionName} · v${release.versionCode} · ${release.releaseId}") } }
        }
    }, confirmButton = { TextButton(onClick = { showCache = false }) { Text("关闭") } })
    migrate?.let { release ->
        var schema by remember(release.releaseId) { mutableStateOf<String?>(null) }
        LaunchedEffect(release.releaseId) { schema = withContext(Dispatchers.IO) {
            val file = File(vm.graph.store.releaseCache(release.catalogPluginId, release.releaseId, plugin.marketSourceId), "config.schema.json")
            if (file.isFile) file.readText() else "{}"
        } }
        AlertDialog(onDismissRequest = { migrate = null }, title = { Text("确认 ${release.versionName} 的配置") }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("旧安装与已发布进程保持原配置，确认后才切换本地发行。以下从新版默认值开始，请按需调整。")
                Text("旧配置：${plugin.configJson}")
                schema?.let { nextSchema ->
                    val defaults = release.manifest.optJSONObject("config")?.getJSONObject("defaults")?.toString() ?: "{}"
                    if (nextSchema == "{}") TextButton(onClick = { vm.action { vm.graph.store.installCachedRelease(project.id, release.catalogPluginId, release.releaseId, "{}", plugin.marketSourceId); migrate = null; "已确认移除配置，旧发布需重新发布才改变" } }) { Text("确认使用无配置的新版本") }
                    else key(release.releaseId) { PluginConfigurationFields(plugin.copy(configSchema = nextSchema, configJson = defaults)) { config -> vm.action {
                        vm.graph.store.installCachedRelease(project.id, release.catalogPluginId, release.releaseId, config, plugin.marketSourceId); migrate = null; "新版本与配置已接收，请显式发布后重启目标"
                    } } }
                }
            }
        }, confirmButton = { TextButton(onClick = { migrate = null }) { Text("取消") } })
    }
    var open by remember { mutableStateOf(false) }
    var catalog by remember(plugin.id) { mutableStateOf(plugin.catalogPluginId ?: "") }
    var publisher by remember(plugin.id) { mutableStateOf(plugin.publisherId ?: "") }
    var version by remember(plugin.id) { mutableStateOf(((plugin.marketVersionCode ?: 0) + 1).toString()) }
    var license by remember { mutableStateOf("GPL-3.0-or-later") }
    var source by remember { mutableStateOf(JSONObject(plugin.releaseManifest).optString("sourceUrl")) }
    var selected by remember { mutableStateOf("") }
    val allPlugins by vm.plugins.collectAsStateWithLifecycle()
    var javaPeer by remember { mutableStateOf("") }
    var dependencies by remember { mutableStateOf("[]") }
    var prepared by remember { mutableStateOf<JSONObject?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val record = prepared
        if (uri != null && record != null) vm.action {
            withContext(Dispatchers.IO) { requireNotNull(vm.graph.contentResolver.openOutputStream(uri)).use { output -> File(record.getString("archivePath")).inputStream().use { it.copyTo(output) } } }
            "固定发行已导出，尚未公开发布"
        }
    }
    TextButton(onClick = { open = true }) { Text("导出预编译发行") }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("准备固定发行") }, text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("发行包含固定产物，可在另一设备导入。作者身份尚未经过市场认证。")
        OutlinedTextField(catalog, { catalog = it }, label = { Text("稳定插件 ID") })
        OutlinedTextField(publisher, { publisher = it }, label = { Text("作者 ID") })
        OutlinedTextField(version, { version = it }, label = { Text("递增 versionCode") })
        Text("请先在插件附件 LICENSE 中保存许可证全文。")
        OutlinedTextField(license, { license = it }, label = { Text("许可证") })
        OutlinedTextField(source, { source = it }, label = { Text("源码 HTTPS 地址") })
        if (plugin.runtime == "NATIVE") builds.filter { it.pluginId == plugin.id && it.state == "SUCCEEDED" }.forEach { build ->
            FilterChip(selected == build.buildId, { selected = build.buildId }, label = { Text("r${build.sourceRevision} · ${build.buildId}") })
        }
        if (plugin.runtime == "NATIVE") {
            Text("可选联合Java组件")
            FilterChip(javaPeer.isEmpty(), { javaPeer = "" }, label = { Text("单Native发行") })
            allPlugins.filter { it.projectId == project.id && it.runtime == "BEANSHELL" && it.processScope == plugin.processScope && it.processName == plugin.processName }.forEach { peer ->
                FilterChip(javaPeer == peer.id, { javaPeer = peer.id }, label = { Text(peer.name) })
            }
        }
        OutlinedTextField(dependencies, { dependencies = it }, label = { Text("固定依赖（sourceId、publisherId、catalogPluginId、releaseId的JSON数组）") })
        TextButton(enabled = plugin.runtime != "NATIVE" || selected.isNotEmpty(), onClick = { vm.action {
            val artifact = builds.firstOrNull { it.buildId == selected }
            val manifest = JSONObject().put("formatVersion", 3).put("catalogPluginId", catalog).put("publisherId", publisher)
                .put("releaseId", UUID.randomUUID().toString()).put("versionCode", version.toLong()).put("versionName", plugin.version)
                .put("name", plugin.name).put("description", plugin.description).put("license", license).put("sourceUrl", source)
                .put("channel", "stable").put("changelog", plugin.description).put("publishedAt", java.time.Instant.now().toString())
                .put("runtime", if (javaPeer.isEmpty()) plugin.runtime else "JAVA_NATIVE").put("dependencies", JSONArray(dependencies)).put("pluginAbi", 1).put("sdkVersion", "p0-v4-api31-m7").put("minHostVersion", 9)
                .put("requiredCapabilities", JSONArray()).put("artifacts", JSONArray().put(JSONObject()
                    .put("abi", if (plugin.runtime == "NATIVE") "arm64-v8a" else "any").put("minApi", artifact?.minApi ?: 31)
                    .put("artifactPath", if (plugin.runtime == "NATIVE") "native/arm64-v8a/plugin.so" else "java/main.java")))
                .put("target", JSONObject().put("processType", plugin.nativeProcessType).put("packageName", project.packageName).put("processScope", plugin.processScope)
                    .put("processName", plugin.processName.ifEmpty { project.packageName }))
            if (javaPeer.isNotEmpty()) manifest.getJSONArray("artifacts").put(JSONObject().put("abi", "any").put("minApi", 31).put("artifactPath", "java/main.java"))
            if (plugin.configSchema != "{}") manifest.put("config", JSONObject().put("schemaPath", "config.schema.json").put("version", 1).put("defaults", JSONObject(plugin.configJson)))
            prepared = vm.graph.releasePreparations.prepare(plugin.id, artifact?.buildId, manifest, javaPeer.takeIf { it.isNotEmpty() })
            exporter.launch("${catalog}-${version}.zip"); "固定发行已准备"
        } }) { Text("准备并导出 ZIP") }
    } }, confirmButton = { TextButton(onClick = { open = false }) { Text("关闭") } })
}
