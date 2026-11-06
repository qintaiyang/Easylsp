package io.github.easylsp.app.platform.market

import io.github.easylsp.app.util.readUpTo
import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.data.PluginEntity
import io.github.easylsp.app.data.PluginArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.IOException
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.UUID

/** Public downloads need no author credential. Verify raw bytes before parsing their payload. */
class MarketService(private val graph: EasylspApp) {
    private val http = OkHttpClient()
    private val root = File(graph.filesDir, "market").apply { mkdirs() }
    private val transferFile = File(root, "download.json")
    val transfer = MutableStateFlow(if (transferFile.exists()) JSONObject(transferFile.readText()).also {
        if (it.optString("status") == "DOWNLOADING") { it.put("status", "INTERRUPTED"); transferFile.writeText(it.toString()) }
    } else JSONObject().put("status", "IDLE"))
    @Volatile private var activeDownload: Call? = null
    private fun transfer(record: JSONObject) { transferFile.writeText(record.toString()); transfer.value = record }
    fun cancelDownload() { activeDownload?.let { transfer(JSONObject(transfer.value.toString()).put("status", "CANCELLED")); it.cancel() } }
    private suspend fun download(url: String, record: JSONObject): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url(url).build()); activeDownload = call
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) { activeDownload = null; if (continuation.isActive) continuation.resumeWithException(error) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        check(it.isSuccessful) { "市场下载 HTTP ${it.code}" }
                        MarketSources.requireHttps(it.request.url.toString())
                        val body = requireNotNull(it.body)
                        val output = ByteArrayOutputStream(); val buffer = ByteArray(65536)
                        body.byteStream().use { input ->
                            while (true) {
                                val size = input.read(buffer); if (size < 0) break
                                require(output.size() + size <= 32 * 1024 * 1024) { "发行超过 32 MiB" }
                                output.write(buffer, 0, size)
                                transfer.value = JSONObject(record.toString()).put("receivedBytes", output.size()).put("totalBytes", body.contentLength())
                            }
                        }; output.toByteArray()
                    }
                    activeDownload = null; if (continuation.isActive) continuation.resume(bytes)
                } catch (error: Exception) { activeDownload = null; if (continuation.isActive) continuation.resumeWithException(error) }
            }
        })
    }
    private val preferences = graph.getSharedPreferences("market-cache", 0)
    private fun jsonEquivalent(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> a.keys().asSequence().toSet() == b.keys().asSequence().toSet() && a.keys().asSequence().all { jsonEquivalent(a.get(it), b.get(it)) }
        a is org.json.JSONArray && b is org.json.JSONArray -> a.length() == b.length() && (0 until a.length()).all { jsonEquivalent(a.get(it), b.get(it)) }
        else -> a == b
    }
    val sources = MarketSources(graph)
    private fun directoryKey(sourceId: String) = if (sourceId == "official") "directory" else "directory:$sourceId"
    internal fun fetch(url: String, limit: Int, sourceId: String = "official"): ByteArray {
        MarketSources.requireHttps(url)
        if (sourceId == "official") require(java.net.URI(url).host in setOf("github.com", "qintaiyang.github.io")) { "官方市场链接必须来自官方 GitHub 来源" }
        return http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "市场下载 HTTP ${response.code}" }
            MarketSources.requireHttps(response.request.url.toString())
            requireNotNull(response.body).byteStream().use { input ->
                val bytes = input.readUpTo(limit + 1)
                require(bytes.size <= limit) { "市场响应超过大小限制" }; bytes
            }
        }
    }
    fun cached(sourceId: String = sources.selected()): JSONObject? = preferences.getString(directoryKey(sourceId), null)?.let { directory ->
        val payload = File(root, "$directory/catalog.json").readBytes()
        val metadata = JSONObject(File(root, "$directory/catalog.signature.json").readText())
        verifiedCatalog(sourceId, metadata, payload)
    }
    private fun verifiedCatalog(sourceId: String, metadata: JSONObject, bytes: ByteArray): JSONObject {
        ReleaseSignature.verify(metadata, bytes, sources.get(sourceId).keys)
        require(metadata.getString("kind") == "catalog")
        val catalog = JSONObject(bytes.toString(Charsets.UTF_8))
        require(catalog.getInt("schema") == 1 && catalog.getString("catalogRevision") == metadata.getString("catalogRevision")) { "目录签名身份不一致" }
        val releases = catalog.getJSONArray("releases")
        val identities = mutableSetOf<String>()
        for (i in 0 until releases.length()) {
            val record = releases.getJSONObject(i)
            io.github.easylsp.app.data.PluginRelease.read(record.toString().toByteArray())
            require(identities.add("${record.getString("publisherId")}:${record.getString("catalogPluginId")}:${record.getString("releaseId")}")) { "目录发行身份重复" }
            record.put("_marketSourceId", sourceId)
        }
        return catalog.put("_marketSourceId", sourceId)
    }
    suspend fun refresh(sourceId: String = sources.selected()): JSONObject = withContext(Dispatchers.IO) {
        val source = sources.get(sourceId)
        val bytes = fetch(source.catalogUrl, 4 * 1024 * 1024, sourceId)
        val signatureUrl = java.net.URI(source.catalogUrl).resolve("catalog.signature.json").toString()
        val signature = fetch(signatureUrl, 8192, sourceId)
        val catalog = verifiedCatalog(sourceId, JSONObject(signature.toString(Charsets.UTF_8)), bytes)
        val directory = UUID.randomUUID().toString()
        File(root, directory).apply { mkdirs(); File(this, "catalog.json").writeBytes(bytes); File(this, "catalog.signature.json").writeBytes(signature) }
        val old = preferences.getString(directoryKey(sourceId), null)
        check(preferences.edit().putString(directoryKey(sourceId), directory).putLong("updatedAt:$sourceId", System.currentTimeMillis()).commit())
        old?.let { File(root, it).deleteRecursively() }; catalog
    }
    fun updatedAt(): Long = preferences.getLong("updatedAt:${sources.selected()}", 0)
    suspend fun install(projectId: String, record: JSONObject): PluginEntity = withContext(Dispatchers.IO) {
        val sourceId = record.optString("_marketSourceId", sources.selected())
        val catalogs = mutableMapOf<String, JSONObject>()
        catalogs[sourceId] = cached(sourceId) ?: refresh(sourceId)
        // Catalogs are fetched before graph traversal so lookup stays synchronous.
        suspend fun loadSources(source: String, manifest: JSONObject, seen: MutableSet<String>) {
            val key = "$source:${manifest.getString("publisherId")}:${manifest.getString("catalogPluginId")}:${manifest.getString("releaseId")}"
            if (!seen.add(key)) return
            require(seen.size <= 64) { "依赖图超过64个发行" }
            for (pin in io.github.easylsp.app.data.ReleaseDependencies.pins(manifest, source)) {
                val id = pin.identity.sourceId
                val catalog = catalogs[id] ?: (cached(id) ?: refresh(id)).also { catalogs[id] = it }
                val rows = catalog.getJSONArray("releases")
                val child = requireNotNull((0 until rows.length()).map { rows.getJSONObject(it) }.find { it.getString("publisherId") == pin.identity.publisherId && it.getString("catalogPluginId") == pin.identity.catalogPluginId && it.getString("releaseId") == pin.releaseId }) { "市场缺少固定依赖：${pin.identity.catalogPluginId}/${pin.releaseId}" }
                loadSources(id, child, seen)
            }
        }
        val rootRows = catalogs.getValue(sourceId).getJSONArray("releases")
        val trustedRoot = requireNotNull((0 until rootRows.length()).map { rootRows.getJSONObject(it) }.find { it.getString("publisherId") == record.getString("publisherId") && it.getString("catalogPluginId") == record.getString("catalogPluginId") && it.getString("releaseId") == record.getString("releaseId") }) { "发行不在此来源的已验签目录内" }
        loadSources(sourceId, trustedRoot, mutableSetOf())
        val plan = io.github.easylsp.app.data.ReleaseDependencies.resolve(sourceId to trustedRoot) { pin ->
            val rows = catalogs.getValue(pin.identity.sourceId).getJSONArray("releases")
            (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("publisherId") == pin.identity.publisherId && it.getString("catalogPluginId") == pin.identity.catalogPluginId && it.getString("releaseId") == pin.releaseId }
        }
        check(activeDownload == null) { "已有发行下载中，请等待或取消" }
        val received = mutableListOf<io.github.easylsp.app.data.ProjectStore.ReceivedRelease>()
        try {
            for ((source, release) in plan) {
                require(release.getString("state") == "PUBLISHED") { "发行或固定依赖已下架：${release.getString("releaseId")}" }
                val progress = JSONObject().put("status", "DOWNLOADING").put("catalogPluginId", release.getString("catalogPluginId")).put("releaseId", release.getString("releaseId")).put("sourceId", source)
                transfer(progress)
                val signature = JSONObject(fetch(release.getString("signatureUrl"), 8192, source).toString(Charsets.UTF_8))
                val url = release.getString("downloadUrl"); MarketSources.requireHttps(url)
                if (source == "official") require(java.net.URI(url).host == "github.com")
                val bytes = download(url, progress)
                ReleaseSignature.verify(signature, bytes, sources.get(source).keys)
                val entries = PluginArchive.read(bytes.inputStream())
                val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
                ReleaseSignature.bindRelease(signature, manifest)
                val expected = JSONObject(release.toString()).apply { listOf("state", "downloadUrl", "signatureUrl", "reportUrl", "reviewUrl", "candidateRunId", "candidateArtifactId", "_marketSourceId").forEach { remove(it) } }
                // Bind dependency declarations and platform metadata to the signed package.
                require(jsonEquivalent(manifest, expected)) { "目录与发行元数据不一致" }
                received.add(io.github.easylsp.app.data.ProjectStore.ReceivedRelease(entries, signature, source))
            }
            graph.store.installReleases(requireNotNull(graph.store.dao.project(projectId)), received).also {
                transfer(JSONObject(transfer.value.toString()).put("status", "INSTALLED").put("releaseCount", received.size))
            }
        } catch (error: Exception) {
            transfer(JSONObject(transfer.value.toString()).put("status", if (error is kotlinx.coroutines.CancellationException || transfer.value.optString("status") == "CANCELLED") "CANCELLED" else "FAILED").put("detail", error.message)); throw error
        }
    }
}
