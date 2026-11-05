package io.github.easylsp.app

import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.*
import io.github.easylsp.app.platform.tools.RunBinding
import io.github.easylsp.app.platform.root.RootCommand
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class NativePluginWorkflowDeviceTest {
    @get:org.junit.Rule val visible = VisibleActivityRule()
    private lateinit var activity: androidx.test.core.app.ActivityScenario<MainActivity>
    @org.junit.Before fun show() { activity = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java) }
    @org.junit.After fun close() { activity.close() }
    private val graph get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
    private suspend fun root(command: String): String {
        val output = StringBuilder()
        val result = graph.executions.runner.execute(UUID.randomUUID().toString(), listOf(graph.root.su, "-c", command), graph.filesDir, closeInput = true, timeoutSeconds = 30) { output.append(it) }
        check(result.exitCode == 0) { output.toString() }; return output.toString().trim()
    }
    private suspend fun fixture(mode: String = "native-plugin") {
        root("am force-stop io.github.easylsp.demo")
        val info = graph.packageManager.getApplicationInfo("io.github.easylsp.demo", 0)
        val file = info.dataDir + "/files/startup-fault.txt"
        root("nsenter -t 1 -m -- /system/bin/sh -c " + RootCommand.quote("mkdir -p ${info.dataDir}/files; printf '$mode\\n' > $file; chown ${info.uid}:${info.uid} $file; chmod 600 $file; restorecon -F $file"))
    }
    @Test fun toolsBuildPublishFixedRevisionAndObserveWithoutJavaSession() = runBlocking<Unit>(Dispatchers.IO) {
        check(graph.root.useRoot()); withTimeout(10000) { while (!graph.framework.state.value.active) delay(50) }
        val project = graph.store.select("io.github.easylsp.demo", "Easylsp Demo", "1")
        val tools = graph.tools.builtins(RunBinding(project, project.sampleRevision, null, "native-device"))
        suspend fun call(name: String, args: JSONObject = JSONObject()) = tools.first { it.name == name }.invoke(args)
        suspend fun await(result: JSONObject) = graph.executions.await(project.id, result.getString("executionId"))
        val prepare = await(call("prepare_native_build_tools")); assertEquals(prepare.toString(2), 0, prepare.getInt("exitCode"))
        assertTrue(call("read_development_reference", JSONObject().put("document", "native-api")).getString("text").isNotEmpty())
        val source = graph.assets.open("early-symbol/src/main.c").bufferedReader().use { it.readText() }
        val created = call("create_plugin", JSONObject().put("runtime", "NATIVE").put("name", "Native fixed build device test").put("source", source))
        val id = created.getString("pluginId")
        val args = JSONObject().put("pluginId", id)
        val evidence = JSONObject().put("projectId", project.id).put("pluginId", id)
        try {
            val build = call("build_native_plugin", JSONObject().put("pluginId", id).put("sourceRevision", 1))
            assertEquals(0, await(build).getInt("exitCode"))
            val publication = call("enable_native_plugin", JSONObject().put("pluginId", id).put("buildId", build.getString("buildId")).put("policy", "NATIVE_ONLY"))
            evidence.put("build", build).put("publication", publication)
            fixture()
            val launched = call("start_target", JSONObject().put("restart", true)); evidence.put("start", launched)
            val status = call("get_native_plugin_status", args); evidence.put("status", status)
            assertTrue(status.toString(), status.getJSONArray("instances").toString().contains("hook_hit"))
            assertNull(graph.bridge.session(project.packageName))
            val logs = root("logcat -d -v raw --pid=${root("pidof io.github.easylsp.demo")} -s EasylspDemoNative:I '*:S'")
            assertTrue(logs, logs.contains("constructor result=1010 errno=33")); evidence.put("target", logs)
            // Saving and a failed compile cannot alter the published build, or the running instance.
            val plugin = requireNotNull(graph.store.dao.plugin(id))
            graph.store.save(id, plugin.name, "", "", "1", "int broken( {")
            val failed = call("build_native_plugin", JSONObject().put("pluginId", id).put("sourceRevision", 2))
            assertNotEquals(0, await(failed).getInt("exitCode"))
            assertTrue(runCatching { call("enable_native_plugin", JSONObject().put("pluginId", id).put("buildId", failed.getString("buildId")).put("policy", "NATIVE_ONLY")) }.isFailure)
            val unchanged = call("get_native_plugin_status", args)
            assertEquals(publication.getString("generation"), unchanged.getString("frameworkGeneration"))
            assertEquals(build.getString("buildId"), unchanged.getJSONObject("published").getString("buildId"))
            assertEquals(1L, unchanged.getJSONArray("instances").getJSONObject(0).getLong("sourceRevision"))
            val missingBuild = requireNotNull(graph.store.nativePlugins.build(build.getString("buildId"))).copy(buildId = UUID.randomUUID().toString(), artifactPath = File(graph.cacheDir, "missing-native-artifact.so").path)
            graph.store.nativePlugins.saveBuild(missingBuild)
            assertTrue(runCatching { graph.nativeDeployments.enable(plugin, missingBuild.buildId, "NATIVE_ONLY") }.isFailure)
            assertEquals("WRITE_ARTIFACT", graph.store.nativePlugins.deployments(project.id).first().failedStep)
            assertEquals(publication.getString("generation"), graph.nativeInstances.status(plugin).getString("frameworkGeneration"))
            graph.store.save(id, plugin.name, "", "", "1", source.replace("+ 1000", "+ 2000"))
            val update = call("build_native_plugin", JSONObject().put("pluginId", id).put("sourceRevision", 3))
            assertEquals(0, await(update).getInt("exitCode"))
            call("enable_native_plugin", JSONObject().put("pluginId", id).put("buildId", update.getString("buildId")).put("policy", "NATIVE_ONLY"))
            val stillOld = call("get_native_plugin_status", args)
            assertEquals(update.getString("buildId"), stillOld.getJSONObject("published").getString("buildId"))
            assertTrue(stillOld.getJSONArray("instances").toString().contains(build.getString("buildId")))
            assertFalse(stillOld.getJSONArray("instances").toString().contains(update.getString("buildId")))
            fixture(); call("start_target", JSONObject().put("restart", true))
            val updatedLog = root("logcat -d -v raw --pid=${root("pidof io.github.easylsp.demo")} -s EasylspDemoNative:I '*:S'")
            assertTrue(updatedLog, updatedLog.contains("constructor result=2010 errno=33")); evidence.put("updated", updatedLog)

            assertTrue(runCatching { call("load_plugin", args) }.exceptionOrNull()?.message.orEmpty().contains("native"))
            val allLogs = call("read_plugin_logs", args)
            assertTrue(allLogs.toString().contains(build.getString("buildId")))
            call("disable_native_plugin", args)
            fixture(); call("start_target", JSONObject().put("restart", true))
            val disabled = root("logcat -d -v raw --pid=${root("pidof io.github.easylsp.demo")} -s EasylspDemoNative:I '*:S'")
            assertTrue(disabled, disabled.contains("constructor result=8 errno=33")); evidence.put("disabled", disabled)
        } finally {
            call("disable_native_plugin", args)
            graph.store.delete(id)
            root("am force-stop io.github.easylsp.demo")
            File(graph.filesDir, "native-workflow-evidence.json").writeText(evidence.toString(2))
        }
    }
    @Test fun addressLifecycleJniAndConflictsReportActualResults() = runBlocking<Unit>(Dispatchers.IO) {
        check(graph.root.useRoot()); withTimeout(10000) { while (!graph.framework.state.value.active) delay(50) }
        var project = graph.store.select("io.github.easylsp.demo", "Easylsp Demo", "1")
        project = graph.samples.capture(project)
        val modules = graph.nativeAnalysis.modules(project.id, project.sampleRevision).getJSONArray("modules")
        val module = (0 until modules.length()).map { modules.getJSONObject(it) }.first { it.getString("name") == "libdemo_early_hook.so" }
        val inspected = graph.nativeAnalysis.inspect(project.id, project.sampleRevision, module.getString("moduleId"))
        assertEquals(inspected.toString(), 0, inspected.getInt("exitCode"))
        val symbols = graph.executions.store.file(inspected.getString("executionId")).readText()
        val address = requireNotNull(Regex("(?m)^\\s*\\d+:\\s+([0-9a-fA-F]+).*easylsp_demo_early_value$").find(symbols)) { symbols }.groupValues[1]
        val buildId = module.getJSONObject("elf").getString("buildId")
        fun asset(name: String) = graph.assets.open("$name/src/main.c").bufferedReader().use { it.readText() }
        val offset = asset("elf-vaddr").replace("REPLACE_FROM_ANALYSIS", buildId).replace("#define TARGET_ELF_VADDR 0x0", "#define TARGET_ELF_VADDR 0x$address")
        val cases = linkedMapOf("address" to offset, "mismatch" to offset.replace(buildId, "00"), "jni" to asset("jni-onload"), "lifecycle" to asset("module-lifecycle"))
        val evidence = JSONObject().put("module", module).put("vaddr", address)
        for ((name, source) in cases) {
            val plugin = graph.store.create(project, "Native $name", source, runtime = "NATIVE")
            var helper: PluginEntity? = null
            try {
                val build = graph.nativeBuilds.build(plugin, 1, project.sampleRevision)
                val result = graph.executions.await(project.id, build.getString("executionId")); assertEquals(result.toString(), 0, result.getInt("exitCode"))
                val artifact = File(requireNotNull(graph.store.nativePlugins.build(build.getString("buildId"))).artifactPath)
                NativePluginArtifact.validate(artifact.readBytes())
                val publication = graph.nativeDeployments.enable(plugin, build.getString("buildId"), "NATIVE_ONLY")
                if (name == "address") {
                    helper = graph.store.create(project, "Conflicting symbol", asset("early-symbol"), runtime = "NATIVE")
                    val duplicate = graph.nativeBuilds.build(helper, 1, project.sampleRevision)
                    assertEquals(0, graph.executions.await(project.id, duplicate.getString("executionId")).getInt("exitCode"))
                    graph.nativeDeployments.enable(helper, duplicate.getString("buildId"), "NATIVE_ONLY")
                }
                if (name == "lifecycle") {
                    helper = graph.store.create(project, "Reload binding", asset("early-symbol"), runtime = "NATIVE")
                    val reload = graph.nativeBuilds.build(helper, 1, project.sampleRevision)
                    assertEquals(0, graph.executions.await(project.id, reload.getString("executionId")).getInt("exitCode"))
                    graph.nativeDeployments.enable(helper, reload.getString("buildId"), "NATIVE_ONLY")
                }
                fixture(if (name == "lifecycle") "native-plugin-lifecycle" else "native-plugin")
                root("am start -W -n io.github.easylsp.demo/.DemoActivity")
                val log = root("logcat -d -v raw --pid=${root("pidof io.github.easylsp.demo")} -s EasylspNative:I EasylspDemoNative:I '*:S'")
                val status = graph.nativeInstances.status(plugin)
                evidence.put(name, JSONObject().put("publication", publication).put("log", log).put("status", status))
                File(graph.filesDir, "native-capabilities-evidence.json").writeText(evidence.toString(2))
                when (name) {
                    "address" -> { assertTrue(log, log.contains("constructor result=1010")); assertTrue(log, log.contains("hook_conflict") && log.contains("owner=${plugin.id}")) }
                    "mismatch" -> { assertTrue(log, log.contains("constructor result=8")); assertTrue(log, log.contains("\"code\":-7")); assertFalse(log.contains("hook_hit")) }
                    "jni" -> { assertTrue(log, log.contains("JNI_OnLoad wrapper before original") && log.contains("JNI_OnLoad wrapper after original")); assertTrue(log.contains("hook_hit")) }
                    "lifecycle" -> {
                        assertTrue(log, log.contains("cycle=1 value=1010") && log.contains("cycle=2 value=1010"))
                        for (phase in listOf("module_before_constructors", "module_after_constructors", "module_before_unload", "hook_installed", "hook_hit", "hook_unbound"))
                            assertEquals(log, 2, log.lineSequence().count { it.contains("\"phase\":\"$phase\"") })
                    }
                }
            } finally {
                graph.nativeDeployments.disable(requireNotNull(graph.store.dao.plugin(plugin.id))); graph.store.delete(plugin.id)
                helper?.let { graph.nativeDeployments.disable(requireNotNull(graph.store.dao.plugin(it.id))); graph.store.delete(it.id) }
                root("am force-stop io.github.easylsp.demo")
            }
        }
    }

    @Test fun processScopesAndJavaCoexistenceUseActualProcessInstances() = runBlocking<Unit>(Dispatchers.IO) {
        check(graph.root.useRoot()); withTimeout(10000) { while (!graph.framework.state.value.active) delay(50) }
        val project = requireNotNull(graph.store.dao.projectByPackage("io.github.easylsp.demo"))
        val source = """
            #include "easylsp_native_plugin.h"
            #include "scope.h"
            __attribute__((visibility("default"))) int easylsp_plugin_init_v1(const sh_host_v1 *host, const sh_plugin_context_v1 *context) {
                host->log(context->instance, scope_message());
                return 0;
            }
        """.trimIndent()
        val plugins = mutableListOf<PluginEntity>()
        try {
            for (scope in listOf("main", "children", "all", "exact")) {
                val created = graph.store.create(project, "Native scope $scope", source, runtime = "NATIVE")
                graph.store.save(created.id, created.name, "", "", "1", source, files = mapOf(
                    "include/scope.h" to "const char *scope_message(void);",
                    "src/nested/scope.c" to "const char *scope_message(void) { return \"process scope initialized\"; }"))
                val plugin = requireNotNull(graph.store.dao.plugin(created.id)).copy(processScope = scope, processName = "io.github.easylsp.demo:worker")
                plugins += plugin; graph.store.dao.updatePlugin(plugin)
                val build = graph.nativeBuilds.build(plugin, plugin.revision, project.sampleRevision)
                assertEquals(0, graph.executions.await(project.id, build.getString("executionId")).getInt("exitCode"))
                graph.nativeDeployments.enable(plugin, build.getString("buildId"), "NATIVE_AND_JAVA")
            }
            root("am force-stop io.github.easylsp.demo")
            root("am start -W -n io.github.easylsp.demo/.DemoActivity")
            root("am startservice -n io.github.easylsp.demo/.DemoProcessService")
            val sessions = withTimeout(15000) {
                graph.bridge.sessions.first { rows -> rows.values.count { it.packageName == project.packageName } == 2 }
            }.values.filter { it.packageName == project.packageName }
            val mainPid = sessions.first { it.processName == project.packageName }.pid
            val workerPid = sessions.first { it.processName.endsWith(":worker") }.pid
            // Binder backfill must carry real early events after Java initialization.
            withTimeout(10000) { while (plugins.any { p -> graph.nativeInstances.events(project.id).none { it.optString("pluginId") == p.id && it.has("sessionId") } }) delay(50) }
            val evidence = JSONObject().put("mainPid", mainPid).put("workerPid", workerPid)
            for (plugin in plugins) {
                val events = graph.nativeInstances.events(project.id).filter { it.optString("pluginId") == plugin.id }
                val actual = events.filter { it.optString("phase") == "plugin_initialized" }.map { it.getInt("pid") }.toSet()
                val expected = when (plugin.processScope) { "main" -> setOf(mainPid); "all" -> setOf(mainPid, workerPid); else -> setOf(workerPid) }
                assertEquals(plugin.processScope, expected, actual)
                evidence.put(plugin.processScope, JSONArray(events))
            }
            File(graph.filesDir, "native-process-scopes-evidence.json").writeText(evidence.toString(2))
        } finally {
            plugins.forEach { graph.nativeDeployments.disable(requireNotNull(graph.store.dao.plugin(it.id))); graph.store.delete(it.id) }
            root("am force-stop io.github.easylsp.demo")
        }
    }

    @Test fun configuredModelGeneratesBuildsPublishesAndVerifies() = runBlocking<Unit>(Dispatchers.IO) {
        check(graph.root.useRoot()); withTimeout(10000) { while (!graph.framework.state.value.active) delay(50) }
        val providerId = requireNotNull(InstrumentationRegistry.getArguments().getString("nativeProviderId"))
        val provider = requireNotNull(graph.store.workflows.provider(providerId))
        val project = requireNotNull(graph.store.dao.projectByPackage("io.github.easylsp.demo"))
        val id = UUID.randomUUID().toString()
        graph.store.workflows.insertConversation(ConversationEntity(id, project.id, provider.id, provider.model,
            "Native 插件生成与早期执行验收", project.sampleRevision, System.currentTimeMillis()))
        fixture()
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("conversationId", id) })
        try {
            graph.chat.submit(id, "已授权执行当前 Demo 项目的 Native 插件任务。目标已准备一次性 native-plugin 启动夹具，下一次冷启动会在构造函数调用 libdemo_early_hook.so 中已确认声明为 int easylsp_demo_early_value(int value) 的函数，参数为 7，原函数返回 value+1 并设 errno=33。请先读 Native SDK，再生成新的 C 插件：先把参数加 4，调用原函数，再把结果加 2008，保持原函数 errno，使构造结果为 2020。使用生产工具创建、编译并读取真实编译输出，明确发布成功 buildId 和 NATIVE_ONLY 策略，重启并读取实际实例的安装/命中和 EasylspDemoNative 的 constructor/JNI_OnLoad 日志。Ubuntu 编译 SDK 已准备好，无需重复安装。无需 Java session，不要调用 Java load_plugin。插件名加 AI 验收，完成后保留发布供测试检查；测试会负责停用。不要重复启动以免一次性夹具被消费。请报告具体 buildId/generation/PID 与返回值证据，不把发布成功等同于问题已解决。", null, true, JSONArray()).await()
            val turn = withTimeout(900000) { graph.store.workflows.turns(id).first { it.isNotEmpty() && it.last().finishedAt != null } }.last()
            val calls = graph.store.workflows.toolRuns(id).first()
            val evidence = JSONObject().put("conversationId", id).put("model", provider.model).put("status", turn.status).put("error", turn.errorDetail)
                .put("tools", JSONArray(calls.map { JSONObject().put("name", it.name).put("status", it.status).put("arguments", it.arguments).put("result", it.result) }))
            File(graph.filesDir, "native-ai-evidence.json").writeText(evidence.toString(2))
            assertEquals(evidence.toString(2), "COMPLETE", turn.status)
            for (tool in listOf("read_development_reference", "create_plugin", "build_native_plugin", "read_execution", "enable_native_plugin", "get_native_plugin_status"))
                assertTrue("AI 未完成 $tool", calls.any { it.name == tool && it.status == "COMPLETE" })
            assertTrue("AI 未冷启动目标", calls.any { it.status == "COMPLETE" &&
                (it.name == "start_target" || it.name == "start_native_diagnostic" && JSONObject(it.arguments).optString("mode", "EXTERNAL_ONLY") in setOf("NORMAL", "EXTERNAL_ONLY")) &&
                JSONObject(it.arguments).optBoolean("restart") })
            val log = root("logcat -d -v raw --pid=${root("pidof io.github.easylsp.demo")} -s EasylspNative:I EasylspDemoNative:I '*:S'")
            evidence.put("target", log); File(graph.filesDir, "native-ai-evidence.json").writeText(evidence.toString(2))
            assertTrue(log, log.contains("constructor result=2020 errno=33") && log.contains("hook_hit"))
        } finally {
            graph.chat.stop()
            graph.store.dao.plugins(project.id).filter { it.runtime == "NATIVE" && it.desiredEnabled }.forEach { graph.nativeDeployments.disable(it) }
            root("am force-stop io.github.easylsp.demo")
        }
    }

    @Test fun selectedPackedTargetHasVersionedBeforeAfterEvidence() = runBlocking<Unit>(Dispatchers.IO) {
        check(graph.root.useRoot()); withTimeout(10000) { while (!graph.framework.state.value.active) delay(50) }
        val project = requireNotNull(graph.store.dao.projectByPackage("cn.jthj.turbobunny"))
        val baseline = graph.diagnostics.start(project, project.sampleRevision, io.github.easylsp.app.platform.diagnostics.DiagnosticMode.ENTRY_ONLY, "*", 8, true)
        graph.executions.await(project.id, baseline.getString("executionId"))
        val source = graph.assets.open("jni-onload/src/main.c").bufferedReader().use { it.readText() }.replace("libdemo_early_hook.so", "libBugly_Native.so")
        val original = graph.store.create(project, "Bugly JNI_OnLoad 原语义时序观测", source, runtime = "NATIVE")
        val plugin = original.copy(processScope = "all"); graph.store.dao.updatePlugin(plugin)
        val evidence = JSONObject().put("projectId", project.id).put("baseline", baseline).put("purpose", "已确认 JNI_OnLoad ABI 的原语义包装；验证时序及退出位置，不臆测或屏蔽退出条件")
        try {
            val build = graph.nativeBuilds.build(plugin, 1, project.sampleRevision)
            val result = graph.executions.await(project.id, build.getString("executionId")); assertEquals(result.toString(2), 0, result.getInt("exitCode"))
            val published = graph.nativeDeployments.enable(plugin, build.getString("buildId"), "NATIVE_ONLY")
            val run = graph.diagnostics.start(project, project.sampleRevision, io.github.easylsp.app.platform.diagnostics.DiagnosticMode.NORMAL, "*", 10, true)
            graph.executions.await(project.id, run.getString("executionId"))
            evidence.put("build", build).put("publication", published).put("diagnostic", run)
                .put("baselineReport", graph.diagnostics.store.read(project.id, baseline.getString("diagnosticRunId")))
                .put("pluginReport", graph.diagnostics.store.read(project.id, run.getString("diagnosticRunId")))
                .put("status", graph.nativeInstances.status(plugin))
            val events = File(graph.diagnostics.store.directory(project.id, run.getString("diagnosticRunId")), "native-plugin-events.jsonl")
                .readLines().filter { it.isNotBlank() }.map(::JSONObject).filter { it.getString("pluginId") == plugin.id }
            evidence.put("events", JSONArray(events))
            File(graph.filesDir, "native-real-target-evidence.json").writeText(evidence.toString(2))
            assertTrue(evidence.toString(2), events.any { it.getString("phase") == "hook_hit" })
        } finally {
            graph.nativeDeployments.disable(plugin)
            root("am force-stop ${project.packageName}")
            File(graph.filesDir, "native-real-target-evidence.json").writeText(evidence.toString(2))
        }
    }

    @Test fun preparePersistence() = runBlocking<Unit>(Dispatchers.IO) {
        withTimeout(10000) { while (!graph.framework.state.value.active) delay(50) }
        val project = requireNotNull(graph.store.dao.projectByPackage("io.github.easylsp.demo"))
        val source = graph.assets.open("early-symbol/src/main.c").bufferedReader().use { it.readText() }
        val plugin = graph.store.create(project, "Native reboot persistence", source, runtime = "NATIVE")
        val build = graph.nativeBuilds.build(plugin, 1, project.sampleRevision)
        assertEquals(0, graph.executions.await(project.id, build.getString("executionId")).getInt("exitCode"))
        val publication = graph.nativeDeployments.enable(plugin, build.getString("buildId"), "NATIVE_ONLY")
        File(graph.filesDir, "native-persistence.json").writeText(publication.put("projectId", project.id).put("pluginId", plugin.id).toString(2))
    }
    @Test fun finishPersistence() = runBlocking<Unit>(Dispatchers.IO) {
        withTimeout(10000) { while (!graph.framework.state.value.active) delay(50) }
        val evidence = JSONObject(File(graph.filesDir, "native-persistence.json").readText())
        val plugin = requireNotNull(graph.store.dao.plugin(evidence.getString("pluginId")))
        graph.nativeDeployments.disable(plugin); graph.store.delete(plugin.id)
    }

}
