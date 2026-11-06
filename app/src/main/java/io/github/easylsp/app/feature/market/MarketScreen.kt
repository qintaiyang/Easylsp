package io.github.easylsp.app.feature.market

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.easylsp.app.MainViewModel
import io.github.easylsp.app.ui.components.AppCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File

@Composable fun MarketScreen(vm: MainViewModel) {
    var sourceId by remember { mutableStateOf(vm.graph.market.sources.selected()) }
    var sourceDialog by remember { mutableStateOf(false) }
    var sourceRows by remember { mutableStateOf(vm.graph.market.sources.list()) }
    var sourceError by remember { mutableStateOf("") }
    var catalog by remember { mutableStateOf<JSONObject?>(null) }
    var query by remember { mutableStateOf("") }
    var runtime by remember { mutableStateOf("ALL") }
    var author by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var installedOnly by remember { mutableStateOf(false) }
    var channel by remember { mutableStateOf("ALL") }
    val installedPlugins by vm.plugins.collectAsStateWithLifecycle()
    var showWithdrawn by remember { mutableStateOf(false) }
    val projects by vm.projects.collectAsStateWithLifecycle()
    val transfer by vm.graph.market.transfer.collectAsStateWithLifecycle()
    LaunchedEffect(sourceId, sourceRows) {
        catalog = null; sourceError = ""
        try { catalog = withContext(Dispatchers.IO) { vm.graph.market.cached(sourceId) } }
        catch (error: Exception) { if (error is kotlinx.coroutines.CancellationException) throw error; sourceError = error.message.orEmpty() }
    }
    fun installed(record: JSONObject) = installedPlugins.filter { it.catalogPluginId == record.getString("catalogPluginId") && it.publisherId == record.getString("publisherId") && it.marketSourceId == sourceId }
    fun compatible(record: JSONObject): String? = try { io.github.easylsp.app.data.PluginRelease.read(record.toString().toByteArray()).platformCompatible(android.os.Build.VERSION.SDK_INT); null } catch (error: IllegalArgumentException) { error.message }
    fun open(url: String) { vm.graph.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    if (author) { AuthorScreen(vm) { author = false }; return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (sourceError.isNotEmpty()) Text("来源缓存未通过验签：$sourceError", color = MaterialTheme.colorScheme.error)
        Text("市场来源", style = MaterialTheme.typography.titleMedium)
        sourceRows.forEach { source -> Row {
            FilterChip(sourceId == source.id, { vm.graph.market.sources.select(source.id); sourceId = source.id; selected = null }, label = { Text(source.name) })
            if (source.id != "official") TextButton(onClick = { vm.graph.market.sources.remove(source.id); sourceRows = vm.graph.market.sources.list(); sourceId = vm.graph.market.sources.selected() }) { Text("移除来源") }
        } }
        TextButton(onClick = { sourceDialog = true }) { Text("添加市场来源") }
        Row { Button(onClick = { vm.action { catalog = vm.graph.market.refresh(); "市场目录已更新" } }) { Text("刷新目录") }; TextButton(onClick = { author = true }) { Text("作者与投稿") } }
        if (transfer.optString("status") != "IDLE") {
            Text("下载 ${transfer.optString("releaseId")} · ${transfer.optString("status")} · ${transfer.optLong("receivedBytes")} 字节")
            if (transfer.optString("status") == "DOWNLOADING") TextButton(onClick = { vm.graph.market.cancelDownload() }) { Text("取消下载") }
        }
        Text(if (catalog == null) "首次使用请联网刷新；浏览与安装无需登录。" else "签名目录 ${catalog!!.getString("catalogRevision")} · 缓存于 ${java.util.Date(vm.graph.market.updatedAt())}", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(query, { query = it }, label = { Text("搜索名称、目标、作者") }, modifier = Modifier.fillMaxWidth())
        Row { listOf("ALL" to "全部", "NATIVE" to "Native", "BEANSHELL" to "Java", "JAVA_NATIVE" to "联合").forEach { (id, label) -> FilterChip(runtime == id, { runtime = id }, label = { Text(label) }) } }
        Row { listOf("ALL" to "所有渠道", "stable" to "稳定", "test" to "测试").forEach { (id, label) -> FilterChip(channel == id, { channel = id }, label = { Text(label) }) } }
        Row { Checkbox(installedOnly, { installedOnly = it }); Text("只看已安装插件") }
        Row { Checkbox(showWithdrawn, { showWithdrawn = it }); Text("显示下架记录") }
        val releases = catalog?.optJSONArray("releases")
        val records = (0 until (releases?.length() ?: 0)).map { releases!!.getJSONObject(it) }
            .filter { (!installedOnly || installed(it).isNotEmpty()) && (channel == "ALL" || it.optString("channel") == channel) && (showWithdrawn || it.getString("state") == "PUBLISHED") && (runtime == "ALL" || it.optString("runtime") == runtime) && (query.isBlank() || listOf(it.optString("name"), it.getString("catalogPluginId"), it.getString("publisherId"), it.optJSONObject("target")?.optString("packageName").orEmpty()).any { field -> field.contains(query, true) }) }
            .sortedByDescending { it.getLong("versionCode") }
        if (records.isEmpty()) Text("没有匹配的发行。")
        records.forEach { record -> AppCard {
            Text(record.optString("name", record.getString("catalogPluginId")), style = MaterialTheme.typography.titleMedium)
            Text("${record.optString("versionName")} · ${record.optString("runtime")} · ${record.getString("publisherId")} · ${record.getString("state")}")
            Text(record.optString("description"))
            val current = installed(record)
            if (current.isNotEmpty()) Text(current.joinToString { "已安装 ${it.releaseId}" } + if (current.any { (it.marketVersionCode ?: 0) < record.getLong("versionCode") }) " · 可更新" else "")
            compatible(record)?.let { Text("不兼容：$it") }
            TextButton(onClick = { selected = record }) { Text("详情与安装") }
        } }
    }
    if (sourceDialog) {
        var id by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }; var keyId by remember { mutableStateOf("") }; var key by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { sourceDialog = false }, title = { Text("信任新的市场来源") }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("只添加你信任的维护者提供的目录和公钥。新来源独立验签，不使用官方市场身份。")
                OutlinedTextField(id, { id = it }, label = { Text("来源ID") })
                OutlinedTextField(name, { name = it }, label = { Text("来源名称") })
                OutlinedTextField(url, { url = it }, label = { Text("HTTPS目录地址（catalog.json）") })
                OutlinedTextField(keyId, { keyId = it }, label = { Text("公钥ID") })
                OutlinedTextField(key, { key = it }, label = { Text("Ed25519 SPKI公钥（Base64）") })
            }
        }, confirmButton = { TextButton(onClick = { vm.action {
            vm.graph.market.sources.save(id, JSONObject().put("name", name).put("catalogUrl", url).put("keys", JSONObject().put(keyId, key.trim())))
            sourceRows = vm.graph.market.sources.list(); sourceDialog = false; "市场来源已保存"
        } }) { Text("信任并保存") } }, dismissButton = { TextButton(onClick = { sourceDialog = false }) { Text("取消") } })
    }
    selected?.let { record -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(record.optString("name", record.getString("catalogPluginId"))) }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("版本 ${record.optString("versionName")} (${record.getLong("versionCode")})\n发行 ${record.getString("releaseId")}\n作者 ${record.getString("publisherId")}")
            Text(record.optString("description")); Text(record.optString("changelog"))
            Text("能力 ${record.getJSONArray("requiredCapabilities")}\n最低宿主 ${record.getInt("minHostVersion")} · ABI ${record.getJSONArray("artifacts").getJSONObject(0).getString("abi")}\n许可 ${record.optString("license")} · ${record.optString("runtime")}\n目标 ${record.optJSONObject("target")?.optString("packageName").orEmpty()}")
            TextButton(onClick = { open(record.getString("sourceUrl")) }) { Text("查看固定源码") }
            TextButton(onClick = { open(record.getString("reportUrl")) }) { Text("举报此发行") }
            Text("来源 $sourceId · 固定依赖 ${record.optJSONArray("dependencies") ?: "[]"}")
            Text("安装后在项目插件中设置配置并显式启用；Native 发布后重启目标应用。")
            val targets = projects.filter { it.kind == "INSTALLED" && it.packageName == record.optJSONObject("target")?.optString("packageName") }
            if (targets.isEmpty()) Text("请先在项目页添加目标应用。")
            targets.forEach { project -> Button(enabled = record.getString("state") == "PUBLISHED" && compatible(record) == null, onClick = { vm.action {
                val plugin = vm.graph.market.install(project.id, record); selected = null; "${plugin.name} 已安装到 ${project.name}，请在项目中配置和启用"
            } }) { Text("安装到 ${project.name}") } }
        }
    }, confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } }) }
}

@Composable private fun AuthorScreen(vm: MainViewModel, back: () -> Unit) {
    var profile by remember { mutableStateOf(vm.graph.github.profile()) }
    var secret by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf(vm.graph.getSharedPreferences("github-market", 0).getString("clientId", "")!!) }
    var device by remember { mutableStateOf<JSONObject?>(null) }
    var deviceMessage by remember { mutableStateOf("") }
    var repository by remember { mutableStateOf("") }
    var commit by remember { mutableStateOf("") }
    var repositories by remember { mutableStateOf<List<String>>(emptyList()) }
    var receipts by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var chosen by remember { mutableStateOf<JSONObject?>(null) }
    suspend fun reload() = withContext(Dispatchers.IO) {
        receipts = File(vm.graph.filesDir, "release-preparations").listFiles().orEmpty().mapNotNull { dir -> File(dir, "preparation.json").takeIf { it.isFile }?.let { JSONObject(it.readText()) } }.sortedByDescending { it.getJSONObject("manifest").optString("publishedAt") }
    }
    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(device) {
        val flow = device ?: return@LaunchedEffect
        var interval = flow.optLong("interval", 5)
        val deadline = System.currentTimeMillis() + flow.getLong("expires_in") * 1000
        try {
            while (System.currentTimeMillis() < deadline) {
                delay(interval * 1000)
                val result = withContext(Dispatchers.IO) { vm.graph.github.pollDeviceFlow(clientId, flow.getString("device_code")) }
                when (result.getString("status")) {
                    "AUTHORIZED" -> { profile = vm.graph.github.profile(); deviceMessage = "已登录"; device = null; return@LaunchedEffect }
                    "authorization_pending" -> deviceMessage = "等待浏览器授权"
                    "slow_down" -> interval += 5
                    else -> { deviceMessage = result.optString("detail", result.getString("status")); device = null; return@LaunchedEffect }
                }
            }
            deviceMessage = "授权码已过期"; device = null
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) { deviceMessage = error.message.orEmpty(); device = null }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = back) { Text("返回市场") }
        Text("GitHub 作者账号", style = MaterialTheme.typography.titleLarge)
        if (profile.has("id")) {
            Text("${profile.getString("login")} · 账号 ${profile.getLong("id")}")
            TextButton(onClick = { vm.graph.github.logout(); profile = vm.graph.github.profile() }) { Text("退出账号") }
        } else {
            OutlinedTextField(clientId, { clientId = it }, label = { Text("启用 Device Flow 的 OAuth Client ID") }, modifier = Modifier.fillMaxWidth())
            Button(enabled = clientId.isNotBlank() && device == null, onClick = { vm.action {
                device = withContext(Dispatchers.IO) { vm.graph.github.beginDeviceFlow(clientId) }
                vm.graph.getSharedPreferences("github-market", 0).edit().putString("clientId", clientId).apply(); "请在浏览器完成 GitHub 授权"
            } }) { Text("浏览器授权") }
            device?.let { flow ->
                Text("授权码：${flow.getString("user_code")}")
                TextButton(onClick = { vm.graph.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(flow.getString("verification_uri"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("打开 GitHub 授权页") }
                TextButton(onClick = { device = null; deviceMessage = "已取消" }) { Text("取消授权") }
            }
            Text(deviceMessage)
            OutlinedTextField(secret, { secret = it }, label = { Text("或使用有 public_repo 权限的个人令牌") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(enabled = secret.isNotBlank(), onClick = { vm.action {
                val credential = secret; secret = ""
                profile = withContext(Dispatchers.IO) { vm.graph.github.tokenLogin(credential) }; "GitHub 已登录；凭据保存在 Android 加密存储"
            } }) { Text("令牌登录") }
        }
        Text("先在项目插件中准备固定发行，再选择下面的记录。源码地址必须是公开仓库的 /tree/完整commit。")
        receipts.forEach { record -> TextButton(onClick = { chosen = record; repository = record.optString("repository"); commit = record.optString("sourceCommit", record.getJSONObject("manifest").optString("sourceUrl").substringAfterLast('/')) }) { Text("${record.getJSONObject("manifest").getString("name")} · ${record.getString("status")} · ${record.getString("preparationId")}") } }
        chosen?.let { record ->
            Text("当前固定发行：${record.getJSONObject("manifest").toString(2)}")
            OutlinedTextField(repository, { repository = it }, label = { Text("作者公开仓库 owner/repo") }, modifier = Modifier.fillMaxWidth())
            TextButton(enabled = profile.has("id"), onClick = { vm.action { val rows = vm.graph.releasePublishing.repositories(); repositories = (0 until rows.length()).map { rows.getJSONObject(it).getString("full_name") }; "已读取公开仓库" } }) { Text("读取我的仓库") }
            repositories.forEach { name -> TextButton(onClick = { repository = name }) { Text(name) } }
            OutlinedTextField(commit, { commit = it }, label = { Text("固定源码 commit ID") }, modifier = Modifier.fillMaxWidth())
            Button(enabled = profile.has("id"), onClick = { vm.action { chosen = vm.graph.releasePublishing.publish(record.getString("preparationId"), repository, commit); reload(); "作者 Release 已上传，尚未投稿" } }) { Text("上传／续作固定发行") }
            Button(enabled = record.has("assetId"), onClick = { vm.action { chosen = vm.graph.releasePublishing.submit(record.getString("preparationId")); reload(); "投稿已提交，等待人工审核" } }) { Text("提交／续作市场 PR") }
            TextButton(onClick = { vm.action { chosen = vm.graph.releasePublishing.status(record.getString("preparationId")); reload(); chosen!!.getString("status") } }) { Text("查询审核与收录状态") }
            listOf("releaseUrl", "prUrl").filter { record.has(it) }.forEach { key -> TextButton(onClick = { vm.graph.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(record.getString(key))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text(record.getString(key)) } }
            Text("状态：${record.getString("status")}。首次作者须通过官方仓库登记数字账号与仓库 ID；PR 合并后仍需维护者批准发布。")
        }
    }
}
