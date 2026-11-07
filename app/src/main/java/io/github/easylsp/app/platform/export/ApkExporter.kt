package io.github.easylsp.app.platform.export

import android.security.keystore.*
import com.android.apksig.ApkSigner
import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.data.PluginEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.*
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.util.*
import java.util.zip.*
import javax.security.auth.x500.X500Principal

/** A signed, manager-independent module, with a stable per-plugin application ID. */
class ApkExporter(private val graph: EasylspApp) {
    companion object {
        fun packageName(id: String) = "io.github.easylsp.generated.p" + UUID.fromString(id).toString().replace("-", "")
        fun packageApk(template: File, output: File, plugin: PluginEntity, target: String, source: String, libraries: Map<String, ByteArray> = emptyMap()) {
            val config = JSONObject().put("id", plugin.id).put("name", plugin.name).put("packageName", target).put("processName", plugin.processName.ifEmpty { target }).put("revision", plugin.revision).put("processScope", plugin.processScope)
            val replacements = mapOf("assets/standalone/config.json" to config.toString().toByteArray(), "assets/standalone/main.java" to source.toByteArray(), "META-INF/xposed/scope.list" to "$target\n".toByteArray()) + libraries.mapKeys { "assets/standalone/lib/${it.key}" }
            val counter = object : FilterOutputStream(output.outputStream()) {
                var position = 0L
                override fun write(value: Int) { out.write(value); position++ }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length); position += length }
            }
            // APK incremental packaging can leave deleted local headers; use the central directory.
            ZipFile(template).use { input -> ZipOutputStream(counter).use { zip ->
                for (entry in input.entries().asSequence()) {
                    val name = entry.name
                    val signature = name.uppercase(Locale.ROOT).let { it == "META-INF/MANIFEST.MF" || it.startsWith("META-INF/") && (it.endsWith(".SF") || it.endsWith(".RSA") || it.endsWith(".DSA") || it.endsWith(".EC")) }
                    if (!signature && name !in replacements) {
                        var bytes = input.getInputStream(entry).use { it.readBytes() }
                        if (name == "AndroidManifest.xml") bytes = BinaryManifest.renamePackage(bytes, "io.github.easylsp.generated.template", packageName(plugin.id))
                        val destination = ZipEntry(name)
                        if (entry.method == ZipEntry.STORED && name != "AndroidManifest.xml") {
                            destination.method = ZipEntry.STORED; destination.size = bytes.size.toLong(); destination.compressedSize = bytes.size.toLong(); destination.crc = entry.crc
                            val padding = ((4 - (counter.position + 30 + name.toByteArray().size + 4) % 4) % 4).toInt()
                            destination.extra = byteArrayOf(0xfe.toByte(), 0xca.toByte(), padding.toByte(), 0) + ByteArray(padding)
                        }
                        zip.putNextEntry(destination); zip.write(bytes); zip.closeEntry()
                    }
                }
                replacements.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            } }
        }
    }
    private fun signer(): ApkSigner.SignerConfig {
        val alias = "standalone-apk"
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keys.containsAlias(alias)) KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .setCertificateSubject(X500Principal("CN=Easylsp Standalone"))
                .setCertificateSerialNumber(BigInteger.ONE).setCertificateNotBefore(Date(0)).setCertificateNotAfter(Date(4102444800000L)).build())
            generateKeyPair()
        }
        val key = keys.getKey(alias, null) as PrivateKey
        return ApkSigner.SignerConfig.Builder("Easylsp", key, listOf(keys.getCertificate(alias) as X509Certificate)).build()
    }
    suspend fun export(pluginId: String): File = withContext(Dispatchers.IO) {
        val plugin = graph.store.dao.plugin(pluginId) ?: error("插件不存在")
        require(plugin.runtime == "BEANSHELL") { "首版尚不支持 native 独立 APK 导出" }
        require(io.github.easylsp.app.data.ReleaseDependencies.installedOrder(plugin, graph.store.dao.plugins(plugin.projectId)).size == 1) { "独立 APK 仅包含单个 Java 插件和库；联合发行或插件依赖请使用完整发布" }
        val project = graph.store.dao.project(plugin.projectId) ?: error("项目不存在")
        val folder = File(graph.cacheDir, "exports").apply { mkdirs() }
        val unsigned = File(folder, "${plugin.id}-unsigned.apk")
        val signed = File(folder, "${plugin.id}-r${plugin.revision}.apk")
        val template = File(folder, "${plugin.id}-template.apk")
        try {
            graph.assets.open("standalone-template.apk").use { input -> template.outputStream().use { input.copyTo(it) } }
            packageApk(template, unsigned, plugin, project.packageName, graph.store.read(pluginId), graph.store.libraries(plugin).associate { it.name to it.readBytes() })
            ApkSigner.Builder(listOf(signer())).setInputApk(unsigned).setOutputApk(signed).setMinSdkVersion(31)
                .setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(false).setV4SigningEnabled(false).build().sign()
            signed
        } finally { unsigned.delete(); template.delete() }
    }
}
