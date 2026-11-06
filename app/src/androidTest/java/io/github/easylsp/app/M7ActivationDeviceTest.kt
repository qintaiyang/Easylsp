package io.github.easylsp.app

import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.*
import io.github.easylsp.app.platform.root.RootCommand
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.*

/** One controlled joint release: real root publication, Java bridge and target execution. */
class M7ActivationDeviceTest {
    @get:org.junit.Rule(order=0) val visible=VisibleActivityRule()
    @get:org.junit.Rule(order=1) val activity=androidx.test.ext.junit.rules.ActivityScenarioRule(MainActivity::class.java)
    @Test fun jointActivationRunsAndStopsBothComponents() = runBlocking<Unit>(Dispatchers.IO) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val graph=instrumentation.targetContext.applicationContext as EasylspApp
        check(graph.root.useRoot())
        withTimeout(15000) { graph.framework.state.first { it.compatible } }
        val existing=graph.store.dao.projectByPackage("io.github.easylsp.demo")
        val project=existing ?: graph.store.select("io.github.easylsp.demo","M7 joint fixture","1")
        check(graph.store.dao.plugins(project.id).none { it.desiredEnabled }) { "Preserve active Demo plugins" }
        check(graph.nativeHost.current(graph.packageManager.getApplicationInfo(project.packageName,0).uid,project.packageName)==null)
        val savedDeployments=graph.store.nativePlugins.deployments(project.id)
        val catalog="m7-"+UUID.randomUUID().toString()
        suspend fun root(script: String): String {
            val output=StringBuilder()
            val result=graph.executions.runner.execute(UUID.randomUUID().toString(),listOf(graph.root.su,"-c",script),graph.filesDir,closeInput=true,timeoutSeconds=30) { output.append(it) }
            check(result.exitCode==0) { output.toString() };return output.toString().trim()
        }
        // am start -W can return just before the target's onCreate emits its result.
        suspend fun executionLogs(pid: Int, expected: String): String = withTimeout(10000) {
            var logs=root("logcat -d -v raw --pid=$pid -s EasylspDemoNative:I '*:S'")
            while (!logs.contains(expected)) {
                delay(100)
                logs=root("logcat -d -v raw --pid=$pid -s EasylspDemoNative:I '*:S'")
            }
            logs
        }
        val entries=instrumentation.context.assets.open("fixed-native/early-symbol-A/plugin-v3.zip").use { PluginArchive.read(it).toMutableMap() }
        val dependencyCatalog=catalog+"-dependency"
        val dependencyEntries=instrumentation.context.assets.open("fixed-native/module-lifecycle/plugin-v3.zip").use { PluginArchive.read(it).toMutableMap() }
        val dependencyManifest=JSONObject(dependencyEntries.getValue("manifest.json").toString(Charsets.UTF_8)).put("catalogPluginId",dependencyCatalog).put("releaseId","dependency-one")
        dependencyEntries["manifest.json"]=dependencyManifest.toString().toByteArray()
        fun pack(files: Map<String,ByteArray>)=ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip -> files.forEach { (path,bytes) -> zip.putNextEntry(ZipEntry(path));zip.write(bytes);zip.closeEntry() } } }.toByteArray()
        val manifest=JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8)).put("catalogPluginId",catalog).put("releaseId","joint-one").put("runtime","JAVA_NATIVE")
        manifest.put("dependencies",JSONArray().put(JSONObject().put("publisherId",dependencyManifest.getString("publisherId")).put("catalogPluginId",dependencyCatalog).put("releaseId","dependency-one")))
        manifest.getJSONArray("artifacts").put(JSONObject().put("abi","any").put("minApi",31).put("artifactPath","java/main.java"))
        entries["manifest.json"]=manifest.toString().toByteArray()
        entries["java/main.java"]=graph.assets.open("replace-add/main.java").use { it.readBytes() }
        val archive=pack(entries)
        val hadStartupFault=root("nsenter -t 1 -m -- run-as io.github.easylsp.demo sh -c "+RootCommand.quote("if [ -f files/startup-fault.txt ]; then echo present; else echo absent; fi"))=="present"
        val startupFault=if (hadStartupFault) root("nsenter -t 1 -m -- run-as io.github.easylsp.demo base64 files/startup-fault.txt") else ""
        try {
            graph.nativeDeployments.switchBackend(project.id,"ZYGISK","NATIVE_AND_JAVA")
            val dependency=graph.store.importPlugin(project,pack(dependencyEntries).inputStream())
            val plugin=graph.store.importPlugin(project,archive.inputStream())
            graph.releaseActivation.enable(plugin)
            val rows=graph.store.dao.plugins(project.id).filter { it.catalogPluginId==catalog }
            assertEquals(2,rows.size);assertTrue(rows.all { it.desiredEnabled })
            val published=graph.store.nativePlugins.published(project.id)!!
            val snapshot=JSONObject(published.snapshot)
            assertEquals(1,snapshot.getJSONArray("javaPlugins").length())
            assertEquals(listOf(dependency.id,plugin.id),(0 until snapshot.getJSONArray("plugins").length()).map { snapshot.getJSONArray("plugins").getJSONObject(it).getString("pluginId") })
            try { graph.releaseActivation.disable(dependency);fail("enabled dependent allowed dependency removal") } catch (_: IllegalArgumentException) {}
            root("am force-stop io.github.easylsp.demo")
            root("nsenter -t 1 -m -- run-as io.github.easylsp.demo sh -c "+RootCommand.quote("mkdir -p files; echo native-plugin > files/startup-fault.txt"))
            root("am start -W -n io.github.easylsp.demo/.DemoActivity")
            root("am start -W -n io.github.easylsp/.app.MainActivity")
            val session=withTimeout(20000) { graph.bridge.sessions.first { sessions -> sessions.values.any { it.packageName==project.packageName && it.processName==project.packageName } } }.values.first { it.packageName==project.packageName && it.processName==project.packageName }
            graph.bridge.commandSession(session.id,"loadPlugin",rows.single { it.runtime=="BEANSHELL" }.id)
            root("am start -W --activity-clear-top -n io.github.easylsp.demo/.DemoActivity --ez verifyAddition true")
            val logs=executionLogs(session.pid,"java add(2,3)=42")
            assertTrue(logs,logs.contains("constructor result=1010"));assertTrue(logs,logs.contains("java add(2,3)=42"))
            graph.releaseActivation.disable(plugin)
            assertTrue(graph.store.dao.plugins(project.id).filter { it.catalogPluginId==catalog }.none { it.desiredEnabled })
            assertTrue(graph.store.dao.plugin(dependency.id)!!.desiredEnabled)
            graph.releaseActivation.disable(dependency)
            assertNull(graph.nativeHost.current(session.uid,project.packageName))
            root("am force-stop io.github.easylsp.demo")
            // The startup fixture is consumed on every launch; exercise Native again after disable.
            root("nsenter -t 1 -m -- run-as io.github.easylsp.demo sh -c "+RootCommand.quote("mkdir -p files; echo native-plugin > files/startup-fault.txt"))
            root("am start -W -n io.github.easylsp.demo/.DemoActivity --ez verifyAddition true")
            val pid=root("pidof io.github.easylsp.demo").toInt()
            val stopped=executionLogs(pid,"java add(2,3)=5")
            assertTrue(stopped,stopped.contains("constructor result=8"));assertTrue(stopped,stopped.contains("java add(2,3)=5"))
            graph.releaseActivation.disable(graph.store.dao.plugin(plugin.id)!!,delete=true)
            assertTrue(graph.store.dao.plugins(project.id).none { it.catalogPluginId==catalog })
            graph.releaseActivation.disable(graph.store.dao.plugin(dependency.id)!!,delete=true)
            File(graph.filesDir,"m7-activation-evidence.json").writeText(JSONObject().put("active",logs).put("disabled",stopped).put("nativePluginIds",JSONArray(listOf(dependency.id,plugin.id))).put("dependencyOrder","dependency then joint Native").put("dependencyRemovalProtected",true).put("status","PASSED").toString(2))
        } finally {
            graph.store.dao.plugins(project.id).firstOrNull { it.catalogPluginId==catalog }?.let { graph.releaseActivation.disable(it,delete=true) }
            graph.store.dao.plugins(project.id).firstOrNull { it.catalogPluginId==dependencyCatalog }?.let { graph.releaseActivation.disable(it,delete=true) }
            if (existing==null) graph.localManagement.deleteProject(project.id)
            else {
                graph.nativeDeployments.switchBackend(project.id,existing.nativeBackend,existing.nativePolicy)
                graph.store.transaction {
                    graph.store.nativePlugins.deleteProjectDeployments(project.id)
                    savedDeployments.forEach(graph.store.nativePlugins::saveDeployment)
                    graph.store.dao.updateProject(existing)
                }
            }
            root("am force-stop io.github.easylsp.demo")
            val restoreFault=if (hadStartupFault) "printf %s "+RootCommand.quote(startupFault)+" | base64 -d > files/startup-fault.txt" else "rm -f files/startup-fault.txt"
            root("nsenter -t 1 -m -- run-as io.github.easylsp.demo sh -c "+RootCommand.quote(restoreFault))
            File(graph.filesDir,"release-cache/$catalog").deleteRecursively()
            File(graph.filesDir,"release-cache/$dependencyCatalog").deleteRecursively()
        }
    }
}
