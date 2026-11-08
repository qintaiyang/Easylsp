package io.github.easylsp.app

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import io.github.easylsp.app.data.PluginEntity
import io.github.easylsp.app.platform.export.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

class StandalonePackagingTest {
    @Test fun externalServiceConsumerCannotBeExportedAlone() {
        val plugin = PluginEntity("12345678-1234-1234-1234-123456789abc", "project", "Consumer")
        val files = mapOf("main.java" to "", "plugin.json" to """{"schema":1,"services":{"requires":{"example.echo":1}}}""")
        assertThrows(IllegalArgumentException::class.java) {
            ApkExporter.packageApk(File("unused"), File("unused"), plugin, "test.target", "", files = files)
        }
    }
    @Test fun realTemplateRepackagesAndSignsAsIndependentModule() {
        val template = File(System.getProperty("easylsp.standaloneTemplate")!!)
        val folder = Files.createTempDirectory("easylsp-apk-").toFile()
        val plugin = PluginEntity("12345678-1234-1234-1234-123456789abc", "project", "Test module", revision = 9, processName = "test.target:worker")
        val unsigned = File(folder, "unsigned.apk"); val signed = File(folder, "signed.apk")
        try {
            val source = "void onLoad() { plugin.log(\"embedded\"); }"
            val files = mapOf("main.java" to source, "plugin.json" to """{"schema":1,"components":[{"id":"sample","path":"src/sample.bsh"}]}""", "src/sample.bsh" to "return this;", "assets/data.txt" to "payload")
            ApkExporter.packageApk(template, unsigned, plugin, "test.target", source, files = files)
            ZipFile(unsigned).use { zip ->
                val config = JSONObject(zip.getInputStream(zip.getEntry("assets/standalone/config.json")).reader().readText())
                assertEquals("test.target:worker", config.getString("processName")); assertEquals(9L, config.getLong("revision"))
                assertEquals("test.target\n", zip.getInputStream(zip.getEntry("META-INF/xposed/scope.list")).reader().readText())
                assertTrue(zip.getInputStream(zip.getEntry("assets/standalone/main.java")).reader().readText().contains("embedded"))
                val program = io.github.easylsp.runtime.PluginProgram.read(zip.getInputStream(zip.getEntry("assets/standalone/program.zip")))
                assertEquals("program-v1", config.getString("programFormat"))
                assertEquals(files, program.files)
                assertNull(zip.getEntry("assets/xposed_init"))
                assertTrue(zip.getInputStream(zip.getEntry("META-INF/xposed/java_init.list")).reader().readText().contains("ModernEntry"))
                val properties = java.util.Properties().apply { zip.getInputStream(zip.getEntry("META-INF/xposed/module.prop")).use { load(it) } }
                assertEquals("101", properties.getProperty("minApiVersion"))
                assertEquals("101", properties.getProperty("targetApiVersion"))
                assertEquals(java.util.zip.ZipEntry.STORED, zip.getEntry("resources.arsc").method)
                val manifest = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
                val decoded = String(manifest, Charsets.UTF_16LE) + String(manifest, Charsets.UTF_8)
                assertTrue(decoded.contains(ApkExporter.packageName(plugin.id)))
                assertFalse(decoded.contains("io.github.easylsp.generated.template"))
                assertFalse(decoded.contains("BridgeService"))
            }
            // AGP creates this same debug key while assembling the test's template dependency.
            val keys = KeyStore.getInstance(File(System.getProperty("easylsp.debugKeystore")!!), "android".toCharArray())
            val signer = ApkSigner.SignerConfig.Builder("fixture", keys.getKey("androiddebugkey", "android".toCharArray()) as PrivateKey, listOf(keys.getCertificate("androiddebugkey") as X509Certificate)).build()
            ApkSigner.Builder(listOf(signer)).setInputApk(unsigned).setOutputApk(signed).setMinSdkVersion(31).setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(false).setV4SigningEnabled(false).build().sign()
            val result = ApkVerifier.Builder(signed).setMinCheckedPlatformVersion(31).build().verify()
            assertTrue(result.errors.toString(), result.isVerified); assertTrue(result.isVerifiedUsingV2Scheme)
            val exportDir = File(System.getProperty("easylsp.packagingResult")!!).apply { mkdirs() }
            signed.copyTo(File(exportDir, "standalone-fixture.apk"), overwrite = true)
        } finally { folder.deleteRecursively() }
    }
}
