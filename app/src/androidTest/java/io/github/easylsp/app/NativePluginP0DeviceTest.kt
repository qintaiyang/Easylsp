package io.github.easylsp.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.ProjectEntity
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Called by verify-native-plugin-p0-device.sh, which kills the manager before target launch. */
class NativePluginP0DeviceTest {
    @get:org.junit.Rule val visible = VisibleActivityRule()
    private val graph get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
    private suspend fun ready(): ProjectEntity {
        check(graph.root.useRoot()) { "P0 requires granted device root" }
        withTimeout(10000) { while (!graph.framework.state.value.active) delay(50) }
        check(graph.linux.installed) { "P0 requires installed phone Ubuntu" }
        val info = graph.packageManager.getPackageInfo("io.github.easylsp.demo", 0)
        return graph.store.select(info.packageName, "Easylsp Demo", info.versionName.orEmpty())
    }
    @Test fun preparePrototype() = runBlocking(Dispatchers.IO) {
        ActivityScenario.launch(MainActivity::class.java).use {
            val project = ready()
            val preparation = graph.nativeBuilds.prepare(project.id)
            val prepareResult = withTimeout(660000) { graph.executions.await(project.id, preparation.getString("executionId")) }
            assertEquals(prepareResult.toString(2), 0, prepareResult.getInt("exitCode"))
            val source = InstrumentationRegistry.getInstrumentation().context.assets.open("early-symbol/src/main.c").bufferedReader().use { it.readText() }
            val build = graph.nativeBuilds.buildPrototype(project.id, UUID.randomUUID().toString(), 1, project.sampleRevision, source)
            val buildResult = withTimeout(180000) { graph.executions.await(project.id, build.getString("executionId")) }
            assertEquals(buildResult.toString(2), 0, buildResult.getInt("exitCode"))
            val publication = graph.nativeDeployments.publishPrototype(project.id, build.getString("buildId"))
            publication.put("projectId", project.id).put("build", build).put("preparation", preparation)
            File(graph.filesDir, "native-p0-publication.json").writeText(publication.toString(2))
        }
    }
    @Test fun disablePrototype() = runBlocking(Dispatchers.IO) {
        ActivityScenario.launch(MainActivity::class.java).use {
            val project = ready()
            graph.nativeDeployments.disablePrototype(project.id)
            Unit
        }
    }
    @Test fun compilerErrorsRemainFailedAndCannotBePublished() = runBlocking(Dispatchers.IO) {
        ActivityScenario.launch(MainActivity::class.java).use {
            val project = ready()
            for (source in listOf("int broken( {", "#include \"easylsp_native_plugin.h\"\nextern int absent(void);\nint easylsp_plugin_init_v1(const sh_host_v1 *h, const sh_plugin_context_v1 *c) { (void)h; (void)c; return absent(); }")) {
                val build = graph.nativeBuilds.buildPrototype(project.id, UUID.randomUUID().toString(), 1, 0, source)
                val result = graph.executions.await(project.id, build.getString("executionId"))
                assertNotEquals(result.toString(), 0, result.getInt("exitCode"))
                assertTrue(result.getString("text").contains("error:"))
                val record = JSONObject(File(graph.nativeBuilds.directory(project.id, build.getString("buildId")), "build.json").readText())
                assertEquals("FAILED", record.getString("state"))
                assertThrows(IllegalArgumentException::class.java) { graph.nativeDeployments.publishPrototype(project.id, build.getString("buildId")) }
            }
        }
    }

    private suspend fun root(script: String): String {
        val output = StringBuilder()
        val result = graph.executions.runner.execute(UUID.randomUUID().toString(), listOf(graph.root.su, "-c", script),
            graph.filesDir, closeInput = true, timeoutSeconds = 30) { output.append(it) }
        check(result.exitCode == 0) { output.toString() }
        return output.toString().trim()
    }
    private suspend fun launchFixture(): String {
        root("am force-stop io.github.easylsp.demo")
        val app = graph.packageManager.getApplicationInfo("io.github.easylsp.demo", 0)
        val q = io.github.easylsp.app.platform.root.RootCommand::quote
        val path = app.dataDir + "/files/startup-fault.txt"
        val write = "mkdir -p ${q(app.dataDir + "/files")}; printf 'native-plugin\\n' > ${q(path)}; chown ${app.uid}:${app.uid} ${q(path)}; chmod 600 ${q(path)}; restorecon -F ${q(path)}"
        root("nsenter -t 1 -m -- /system/bin/sh -c ${q(write)}")
        root("am start -W -n io.github.easylsp.demo/.DemoActivity")
        val pid = root("pidof io.github.easylsp.demo").toInt()
        return root("logcat -d -v raw --pid=$pid -s EasylspNative:I EasylspDemoNative:I '*:S'")
    }
    @Test fun existingMissedStageAndFailedInitializationHaveDistinctOutcomes() = runBlocking(Dispatchers.IO) {
        ActivityScenario.launch(MainActivity::class.java).use {
            val project = ready()
            val early = InstrumentationRegistry.getInstrumentation().context.assets.open("early-symbol/src/main.c").bufferedReader().use { it.readText() }
            val existing = """
                #include "easylsp_native_plugin.h"
                #include <dlfcn.h>
                #include <stdio.h>
                static int (*original)(int);
                static const sh_host_v1 *host;
                static void *instance;
                static sh_hook_id hook;
                static int replacement(int value) {
                    int result = original(value);
                    if (value == -1234567) { host->hit(instance, hook); return result + 1000; }
                    return result;
                }
                int easylsp_plugin_init_v1(const sh_host_v1 *h, const sh_plugin_context_v1 *c) {
                    host = h; instance = c->instance;
                    int code = h->hook_symbol(instance, "libc.so", "abs", SH_EXISTING_OR_FUTURE,
                        (void *)replacement, (void **)&original, &hook);
                    if (code) return code;
                    int (*function)(int) = (int (*)(int))dlsym(RTLD_DEFAULT, "abs");
                    int value = function(-1234567);
                    char message[80]; snprintf(message, sizeof(message), "existing result=%d", value);
                    h->log(instance, message);
                    return value == 1235567 ? 0 : -99;
                }
            """.trimIndent()
            val cases = linkedMapOf(
                "existing" to existing,
                "missed" to existing.replace("SH_EXISTING_OR_FUTURE", "SH_REQUIRE_BEFORE_CONSTRUCTORS"),
                "failed-init" to early.replace("return result == SH_OK || result == SH_WAITING ? SH_OK : result;", "return result == SH_OK || result == SH_WAITING ? -99 : result;"),
                "missing-symbol" to early.replace("\"easylsp_demo_early_value\"", "\"easylsp_demo_nonexistent\"")
            )
            try {
                for ((name, source) in cases) {
                    val build = graph.nativeBuilds.buildPrototype(project.id, UUID.randomUUID().toString(), 1, project.sampleRevision, source)
                    val result = graph.executions.await(project.id, build.getString("executionId"))
                    assertEquals(result.toString(2), 0, result.getInt("exitCode"))
                    val publication = graph.nativeDeployments.publishPrototype(project.id, build.getString("buildId"))
                    val logs = launchFixture()
                    File(graph.filesDir, "native-p0-$name.log").writeText(logs)
                    val events = logs.lineSequence().filter { it.startsWith("{\"schema\":1,") }.map(::JSONObject)
                        .filter { it.getString("generation") == publication.getString("generation") }.toList()
                    assertTrue(logs, events.isNotEmpty())
                    assertTrue(logs, logs.contains("constructor result=8 errno=33"))
                    when (name) {
                        "existing" -> {
                            assertTrue(logs, logs.contains("existing result=1235567"))
                            assertTrue(events.any { it.getString("phase") == "hook_installed_existing" && it.getInt("code") == 0 })
                            assertTrue(events.any { it.getString("phase") == "hook_hit" })
                        }
                        "missed" -> {
                            assertTrue(events.any { it.getString("phase") == "hook_failed" && it.getInt("code") == -3 })
                            assertTrue(events.any { it.getString("phase") == "plugin_init_failed" })
                        }
                        "failed-init" -> {
                            assertTrue(events.any { it.getString("phase") == "failed_init_cleanup" && it.getInt("code") == 0 })
                            assertTrue(events.any { it.getString("phase") == "plugin_init_failed" && it.getInt("code") == -99 })
                        }
                        "missing-symbol" -> {
                            assertTrue(events.any { it.getString("phase") == "plugin_initialized" })
                            assertTrue(events.any { it.getString("phase") == "hook_failed" && it.getInt("code") > 1 })
                        }
                    }
                    if (name != "existing") assertFalse(events.any { it.getString("phase") == "hook_hit" })
                }
            } finally {
                graph.nativeDeployments.disablePrototype(project.id)
                root("am force-stop io.github.easylsp.demo")
            }
        }
    }
}
