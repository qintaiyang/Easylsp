package io.github.easylsp.app

import io.github.easylsp.app.data.*
import io.github.easylsp.app.platform.market.ReleaseSignature
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginReleaseTest {
    private fun manifest() = JSONObject("""{"formatVersion":3,"catalogPluginId":"fixture.java","publisherId":"fixture","releaseId":"one","versionCode":1,"versionName":"1","name":"Fixture","description":"fixture","license":"MIT","sdkVersion":"v1","channel":"test","publishedAt":"2026-11-06T00:00:00Z","sourceUrl":"https://example.com/source","changelog":"initial","runtime":"BEANSHELL","pluginAbi":1,"minHostVersion":9,"requiredCapabilities":["java.beanshell"],"target":{"packageName":"fixture.demo","processScope":"main"},"artifacts":[{"abi":"any","minApi":34,"artifactPath":"java/main.java"}]}""")
    private fun archive(manifest: JSONObject, extra: String? = null): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            val entries = linkedMapOf("manifest.json" to manifest.toString(), "README.md" to "fixture", "LICENSE" to "MIT license fixture", "java/main.java" to "void onLoad() {}")
            extra?.let { entries[it] = "forbidden" }
            entries.forEach { (path, text) -> zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry() }
        }
    }.toByteArray()
    @Test fun offlineJavaReleaseAndInputBoundaries() {
        assertTrue("java/main.java" in PluginArchive.read(archive(manifest()).inputStream()))
        assertThrows(IllegalArgumentException::class.java) { PluginArchive.read(archive(manifest().put("requiredCapabilities", org.json.JSONArray().put("native.unsupported-future"))).inputStream()) }
        assertThrows(IllegalArgumentException::class.java) { PluginArchive.read(archive(manifest(), "../escape").inputStream()) }
        assertThrows(IllegalArgumentException::class.java) { PluginArchive.read(archive(manifest(), "install.sh").inputStream()) }
        assertThrows(IllegalArgumentException::class.java) { PluginRelease.read(manifest().put("minHostVersion", 10).toString().toByteArray()) }
    }
    @Test fun platformCompatibilityPreservesHistoricalReleases() {
        val old = PluginRelease.read(manifest().toString().toByteArray())
        old.platformCompatible(34)
        old.platformCompatible(36)
        assertThrows(IllegalArgumentException::class.java) { old.platformCompatible(31) }
        val current = manifest().apply { getJSONArray("artifacts").getJSONObject(0).put("minApi", 31) }
        PluginRelease.read(current.toString().toByteArray()).platformCompatible(31)
    }
    @Test fun jointReleaseRequiresBothFixedArtifactsAndPinnedDependencies() {
        val joint=manifest().put("runtime","JAVA_NATIVE").put("dependencies",org.json.JSONArray().put(JSONObject().put("publisherId","other").put("catalogPluginId","other.native").put("releaseId","fixed")))
        assertThrows(IllegalArgumentException::class.java) { PluginRelease.read(joint.toString().toByteArray()) }
        joint.getJSONArray("artifacts").put(JSONObject().put("abi","arm64-v8a").put("minApi",31).put("artifactPath","native/arm64-v8a/plugin.so"))
        val release=PluginRelease.read(joint.toString().toByteArray())
        assertEquals(listOf("NATIVE","BEANSHELL"),release.runtimes)
        assertThrows(IllegalArgumentException::class.java) { release.platformCompatible(31) }
        joint.getJSONArray("dependencies").getJSONObject(0).put("releaseId","../latest")
        assertThrows(IllegalArgumentException::class.java) { PluginRelease.read(joint.toString().toByteArray()) }
    }
    @Test fun specialProcessBelongsToItsActualPackage() {
        val native=manifest().put("runtime","NATIVE").apply {
            getJSONArray("artifacts").getJSONObject(0).put("abi","arm64-v8a").put("artifactPath","native/arm64-v8a/plugin.so")
            getJSONObject("target").put("processScope","exact").put("processType","isolated")
        }
        for (process in listOf("com.google.android.webview:sandboxed_process0", "fixture.demo", "fixture.demo:")) {
            native.getJSONObject("target").put("processName",process)
            assertThrows(IllegalArgumentException::class.java) { PluginRelease.read(native.toString().toByteArray()) }
        }
        native.getJSONObject("target").put("processName","fixture.demo:isolated:fixture.demo.Service")
        PluginRelease.read(native.toString().toByteArray())
        native.getJSONObject("target").put("processType","app_zygote")
        PluginRelease.read(native.toString().toByteArray())
        native.getJSONObject("target").put("processType","app").put("processName","fixture.demo")
        PluginRelease.read(native.toString().toByteArray())
    }
    @Test fun configurationRejectsUnknownFieldsRangesAndUnsupportedConstraints() {
        val schema = JSONObject("""{"type":"object","additionalProperties":false,"required":["count"],"properties":{"count":{"type":"integer","minimum":1,"maximum":5},"mode":{"type":"string","enum":["a","b"]}}}""")
        PluginConfiguration.validate(schema, JSONObject("""{"count":2,"mode":"a"}"""))
        for (values in listOf("{}", "{\"count\":6}", "{\"count\":1.2}", "{\"count\":2,\"mode\":\"c\"}", "{\"count\":2,\"unknown\":true}"))
            assertThrows(Exception::class.java) { PluginConfiguration.validate(schema, JSONObject(values)) }
        schema.getJSONObject("properties").getJSONObject("mode").put("pattern", ".*")
        assertThrows(IllegalArgumentException::class.java) { PluginConfiguration.validate(schema, JSONObject("{\"count\":2}")) }
    }
    @Test fun signatureBindsPayloadDomainIdentityAndTrustedKey() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val metadata = JSONObject().put("schema", 1).put("kind", "release").put("keyId", "test")
            .put("catalogPluginId", "fixture.java").put("publisherId", "fixture").put("releaseId", "one").put("versionCode", 1)
        val payload = archive(manifest())
        val signer = Signature.getInstance("Ed25519"); signer.initSign(pair.private); signer.update(ReleaseSignature.input(metadata, payload))
        metadata.put("signature", Base64.getEncoder().encodeToString(signer.sign()))
        val keys = mapOf("test" to pair.public.encoded)
        ReleaseSignature.verify(metadata, payload, keys); ReleaseSignature.bindRelease(metadata, manifest())
        assertThrows(IllegalArgumentException::class.java) { ReleaseSignature.verify(metadata, payload + 0, keys) }
        assertThrows(IllegalArgumentException::class.java) { ReleaseSignature.verify(metadata, payload, emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { ReleaseSignature.bindRelease(metadata, manifest().put("releaseId", "other")) }
        metadata.put("publisherId", "other")
        assertThrows(IllegalArgumentException::class.java) { ReleaseSignature.verify(metadata, payload, keys) }
    }
}
