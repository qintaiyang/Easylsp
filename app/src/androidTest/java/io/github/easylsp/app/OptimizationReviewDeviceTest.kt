package io.github.easylsp.app

import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.platform.bridge.BridgeHub
import io.github.easylsp.app.platform.diagnostics.DiagnosticMode
import io.github.easylsp.app.platform.config.ModelConfigurationService
import io.github.easylsp.app.platform.harness.HarnessConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Staged fixture: the shell can remove the manager process between prepare and online checks. */
class OptimizationReviewDeviceTest {
    @get:org.junit.Rule val visible = VisibleActivityRule()
    private val graph get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EasylspApp
    private val fixture get() = File(graph.filesDir, "optimization-review-fixture.json")
    private val evidence get() = File(graph.filesDir, "optimization-review-evidence.jsonl")
    private val target = "io.github.easylsp.demo"
    private suspend fun ready() {
        assertTrue(graph.root.useRoot())
        withTimeout(15000) { graph.framework.state.first { it.compatible } }
    }
    private suspend fun shell(script: String): String {
        val out = StringBuilder()
        val result = graph.executions.runner.execute(UUID.randomUUID().toString(), listOf(graph.root.su, "-c", script), graph.filesDir, closeInput = true, timeoutSeconds = 30) { out.append(it) }
        assertEquals(out.toString(), 0, result.exitCode)
        return out.toString().trim()
    }
    private suspend fun connect(): BridgeHub.Session {
        graph.sendBroadcast(Intent("io.github.easylsp.RECONNECT").setPackage(target))
        return withTimeout(15000) { graph.bridge.sessions.first { rows -> rows.values.any { it.packageName == target && it.processName == target && it.startTicks != null } }.values.first { it.packageName == target && it.processName == target } }
    }
    private suspend fun call(session: BridgeHub.Session, operation: String, arguments: Bundle = Bundle(), plugin: String? = null) =
        JSONObject(graph.bridge.commandSession(session.id, operation, plugin, arguments))
    private suspend fun actual(session: BridgeHub.Session): Int = call(session, "debugOnce", Bundle().apply {
        putString("source", "new io.github.easylsp.demo.Calculator().add(2,3);")
    }).getInt("result")

    @Test fun modelOverridesPersistAndApplyToNextTurnOnly() = runBlocking<Unit>(Dispatchers.IO) {
        val provider = graph.models.configureProvider(null, JSONObject().put("name", "Optimization model fixture")
            .put("baseUrl", "http://127.0.0.1:11434/v1").put("authMode", "none"))
        val options = JSONObject().put("contextWindow", 32768).put("maxOutputTokens", 1024)
            .put("reasoningEfforts", JSONArray(listOf("low", "high", "max"))).put("reasoningEffort", "high")
        val first = graph.models.configureModel(null, provider.id, "fixture-one", options)
        val second = graph.models.configureModel(null, provider.id, "fixture-two", options)
        val a = graph.conversations.create(modelId = first.id, title = "Optimization reasoning A")
        val b = graph.conversations.create(modelId = first.id, title = "Optimization reasoning B")
        try {
            fun config(service: ModelConfigurationService, conversation: io.github.easylsp.app.data.ConversationEntity) =
                HarnessConfig.model(service.forConversation(conversation), "", "fixture")
            val running = config(graph.models, a)
            graph.models.setReasoning(a, "max")
            assertEquals("high", running.getString("reasoningEffort"))
            assertEquals("max", config(ModelConfigurationService(graph), a).getString("reasoningEffort"))
            assertEquals("high", config(graph.models, b).getString("reasoningEffort"))
            assertEquals("local-no-auth", running.getString("secret"))
            try { graph.models.setReasoning(a, "turbo"); fail("unsupported effort") } catch (_: IllegalArgumentException) {}
            assertEquals("max", graph.models.reasoning(a.id))
            graph.models.setReasoning(a, "")
            assertEquals("high", config(graph.models, a).getString("reasoningEffort"))
            graph.models.setReasoning(a, "max")
            graph.models.select(a, second.id)
            assertEquals("", graph.models.reasoning(a.id))
            val updated = graph.models.configureModel(first.id, provider.id, first.remoteModelId, JSONObject().put("contextWindow", 64000))
            assertEquals("user", JSONObject(updated.options).getJSONObject("capabilitySources").getString("contextWindow"))
            evidence.appendText("{\"stage\":\"model-overrides\",\"persisted\":true,\"isolated\":true,\"nextTurn\":true,\"noAuth\":true}\n")
        } finally {
            for (conversation in listOf(a, b)) {
                graph.getSharedPreferences("model-selection", 0).edit().remove("reasoning:" + conversation.id).commit()
                graph.store.workflows.deleteGlobalConversation(conversation.id)
            }
            graph.store.workflows.saveModel(first.copy(enabled = false)); graph.store.workflows.saveModel(second.copy(enabled = false))
            graph.models.configureProvider(provider.id, JSONObject().put("enabled", false))
        }
    }

    /** Existing runtime directories are moved aside and restored; this exercises the real first-use entry. */
    @Test fun freshEnvironmentPreparationAndReuse() = runBlocking<Unit>(Dispatchers.IO) {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(graph.root.useRoot())
            assertEquals("stopped", graph.harness.refresh().getString("status"))
            val oldPreparation = graph.harness.preparationId.value
            val moved = mutableListOf<Pair<File, File>>()
            try {
                for (name in listOf("linux", "harness")) {
                    val current = File(graph.filesDir, name)
                    val backup = File(graph.filesDir, "$name-optimization-original")
                    check(!backup.exists()) { "Unrestored environment fixture: $backup" }
                    if (current.exists()) { check(current.renameTo(backup)); moved += current to backup }
                    check(current.mkdirs())
                }
                graph.harness.prepareAndWait()
                val first = JSONObject(File(graph.filesDir, "harness/installed.json").readText())
                assertEquals("24.11.0", first.getString("node"))
                assertTrue(graph.harness.preparationPage()!!.getString("text").contains("[APT]"))
                graph.harness.prepareAndWait()
                assertEquals(first.getLong("installedAt"), JSONObject(File(graph.filesDir, "harness/installed.json").readText()).getLong("installedAt"))
                val text = StringBuilder()
                val result = graph.executions.runner.execute(UUID.randomUUID().toString(), graph.linux.environmentCommand(
                    "node --version; command -v ldconfig; command -v start-stop-daemon; test \"\$TMPDIR\" = /tmp; dpkg --no-act --configure --pending"), graph.filesDir,
                    closeInput = true, timeoutSeconds = 30) { text.append(it) }
                assertEquals(text.toString(), 0, result.exitCode)
                okhttp3.mockwebserver.MockWebServer().use { server ->
                    val event = JSONObject().put("id", "probe-reply").put("model", "fixture").put("choices", JSONArray().put(JSONObject()
                        .put("index", 0).put("delta", JSONObject().put("role", "assistant").put("content", "OK")).put("finish_reason", "stop")))
                    server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $event\n\ndata: [DONE]\n\n"))
                    server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
                    val provider = io.github.easylsp.app.data.ProviderEntity("fresh-probe", "Fresh probe", "openai", server.url("/v1").toString().trimEnd('/'), "fixture",
                        options = JSONObject().put("contextWindow", 32768).put("maxOutputTokens", 1024).put("authMode", "none").toString())
                    val probe = graph.harness.testModel(provider, "")
                    assertTrue(probe, probe.contains("\"probe\":\"complete\""))
                    assertEquals(1, server.requestCount)
                }
                evidence.appendText(JSONObject().put("stage", "fresh-environment").put("installed", first).put("reusePreservedInstalledAt", true)
                    .put("packagedModelProbeCompleted", true).put("commands", text).toString() + "\n")
            } finally {
                withContext(NonCancellable) {
                    graph.harness.stop()
                    for ((current, backup) in moved.asReversed()) {
                        shell("rm -rf " + io.github.easylsp.app.platform.root.RootCommand.quote(current.path))
                        check(backup.renameTo(current))
                    }
                    graph.harness.preparationId.value = oldPreparation
                    graph.getSharedPreferences("ai-preparation", 0).edit().putString("executionId", oldPreparation).commit()
                    graph.harness.refresh()
                }
            }
        }
    }

    @Test fun preparePublishedJava() = runBlocking<Unit>(Dispatchers.IO) {
        ActivityScenario.launch(MainActivity::class.java).use {
            ready()
            check(!fixture.exists()) { "先恢复上次 optimization-review 夹具" }
            val project = graph.store.select(target, "Easylsp Demo", "1")
            val saved = File(graph.filesDir, "java-publications/${project.id}.json")
            val app = requireNotNull(graph.targetApplications.info(target, project.userId).applicationInfo)
            val nativeKey = app.uid.toString() + ":" + target
            val state = JSONObject().put("projectId", project.id).put("previousEnabled", JSONArray(graph.store.dao.plugins(project.id).filter { it.desiredEnabled }.map { it.id }))
                .put("previousPublication", if (saved.exists()) JSONObject(saved.readText()) else JSONObject.NULL).put("plugins", JSONArray())
                .put("previousNative", graph.framework.nativeSnapshot(nativeKey)?.let(::JSONObject) ?: JSONObject.NULL)
            fixture.writeText(state.toString())
            graph.framework.publishNativeSnapshot(nativeKey, JSONObject().put("schema", 1).put("backend", "LIBXPOSED").put("generation", UUID.randomUUID().toString())
                .put("uid", app.uid).put("packageName", target).put("targetApkPath", app.sourceDir).put("policy", "JAVA_ONLY").put("plugins", JSONArray()))
            graph.store.dao.plugins(project.id).filter { it.desiredEnabled }.forEach { plugin -> graph.store.dao.updatePlugin(plugin.copy(desiredEnabled = false)) }
            for ((name, process) in listOf("main" to target, "worker" to "$target:worker")) {
                val source = """
                    void mark(String value) {
                        java.io.FileWriter writer = new java.io.FileWriter(new java.io.File(context.getFilesDir(), "optimization-review-" + android.app.Application.getProcessName().replace(':','_') + ".txt"), true);
                        writer.write(value + "\n"); writer.close();
                    }
                    void onLoad() {
                        ${if (name == "main") "if (fixture.LibraryFixture.answer() != 73) throw new RuntimeException(\"library mismatch\");" else ""}
                        mark("LOAD " + plugin.revision);
                        hook.after("io.github.easylsp.demo.Calculator", "add", new String[]{"int","int"}, new HookCallback() {
                            public void call(HookParam p) { p.setResult(Integer.valueOf(((Integer)p.getResult()).intValue() + 37)); mark("HIT " + p.getResult()); }
                        });
                    }
                    void onUnload() { mark("UNLOAD"); }
                """.trimIndent()
                val plugin = graph.store.create(project, "Optimization review $name", source, processName = process)
                state.getJSONArray("plugins").put(plugin.id); fixture.writeText(state.toString())
                if (name == "main") graph.store.addLibrary(plugin.id, "fixture.dex", InstrumentationRegistry.getInstrumentation().context.assets.open("library-fixture.dex").use { input -> input.readBytes() })
                graph.store.dao.updatePlugin(requireNotNull(graph.store.dao.plugin(plugin.id)).copy(desiredEnabled = true))
            }
            val publication = graph.javaDeployments.publish(project.id, refresh = true)
            assertEquals(2, publication.getJSONArray("plugins").length())
            state.put("publication", publication); fixture.writeText(state.toString())
            evidence.appendText(JSONObject().put("stage", "published").put("snapshot", publication).toString() + "\n")
        }
    }

    @Test fun onlineLifecycleInspectorAndEnvironment() = runBlocking<Unit>(Dispatchers.IO) {
        ActivityScenario.launch(MainActivity::class.java).use {
            ready()
            val state = JSONObject(fixture.readText())
            val project = requireNotNull(graph.store.dao.project(state.getString("projectId")))
            val plugin = state.getJSONArray("plugins").getString(0)
            var host = connect()
            assertEquals(42, actual(host))
            graph.bridge.commandSession(host.id, "getPluginState", plugin)
            val first = graph.bridge.instances.value.getValue("${host.id}/$plugin")
            graph.bridge.commandSession(host.id, "syncPlugins")
            assertEquals(first.instanceId, graph.bridge.instances.value.getValue("${host.id}/$plugin").instanceId)
            call(host, "hookMetrics", Bundle().apply { putBoolean("enabled", true) }, plugin)
            assertEquals(42, actual(host))
            val metrics = call(host, "hookMetrics", plugin = plugin)
            assertEquals(1, metrics.getInt("active")); assertTrue(metrics.getLong("hits") > 0)
            call(host, "performanceStart")
            call(host, "creationStacks", Bundle().apply { putBoolean("enabled", true) })
            val tree = call(host, "viewTree", Bundle().apply { putString("query", "调用 add(2,3)") })
            val rows = tree.getJSONArray("items")
            val button = (0 until rows.length()).map { rows.getJSONObject(it) }.first { it.getBoolean("clickable") }
            val node = Bundle().apply { putString("path", button.getString("path")); putString("snapshotId", tree.getString("snapshotId")) }
            assertTrue(call(host, "viewClick", node).getBoolean("performed"))
            assertTrue(call(host, "viewNode", Bundle(node).apply { putString("relation", "parent") }).getJSONObject("node").has("path"))
            call(host, "viewTree")
            try { call(host, "viewClick", node); fail("stale snapshot must be rejected") } catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("快照")) }
            val address = shell("head -n 1 /proc/${host.pid}/maps").substringBefore('-')
            val memory = call(host, "memorySnapshot", Bundle().apply { putString("address", "0x$address"); putInt("length", 16); putString("diagnosticRunId", "optimization-review") })
            assertEquals(host.pid, memory.getInt("pid")); assertEquals(host.startTicks, memory.getLong("startTicks")); assertFalse(memory.getBoolean("executionEvidence"))
            val previousSession = host.id
            graph.bridge.commandSession(host.id, "disconnectDebug")
            withTimeout(5000) { graph.bridge.sessions.first { !it.containsKey(previousSession) } }
            host = connect()
            assertNotEquals(previousSession, host.id)
            try { graph.bridge.resolve(project, previousSession); fail("stale session") } catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("candidates=")) }
            assertEquals(42, actual(host))
            graph.bridge.commandSession(host.id, "getPluginState", plugin)
            assertEquals(first.instanceId, graph.bridge.instances.value.getValue("${host.id}/$plugin").instanceId)
            try { call(host, "performanceRead"); fail("sampling must stop on disconnect") } catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("先开始采样")) }
            val current = requireNotNull(graph.store.dao.plugin(plugin))
            val validSource = graph.store.read(plugin)
            graph.store.save(plugin, current.name, "", "", "1", "void onLoad( {")
            try { graph.bridge.commandSession(host.id, "reloadPlugin", plugin); fail("bad reload") } catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("ParseException")) }
            assertEquals(42, actual(host))
            graph.store.save(plugin, current.name, "", "", "1", validSource)
            graph.bridge.commandSession(host.id, "syncPlugins")
            assertEquals(first.revision, graph.bridge.instances.value.getValue("${host.id}/$plugin").revision)
            assertEquals(42, actual(host))
            val diagnostic = graph.diagnostics.start(project, project.sampleRevision, DiagnosticMode.EXTERNAL_ONLY, "*", 5, false)
            graph.executions.await(project.id, diagnostic.getString("executionId"))
            val report = graph.diagnostics.read(project.id, diagnostic.getString("diagnosticRunId"))
            assertEquals("NO_EXIT_EVIDENCE", report.getJSONObject("conclusion").getString("classification"))
            val observed = report.getJSONArray("processes")
            assertTrue((0 until observed.length()).any { observed.getJSONObject(it).optInt("pid") == host.pid && observed.getJSONObject(it).optBoolean("aliveAtEnd") })
            val command = graph.linux.command(project.id, "set -e; command -v node; node --version; command -v ldconfig; command -v start-stop-daemon; test \"\$TMPDIR\" = /tmp; printf REVIEW_READABLE > optimization-review-permissions.txt")
            val id = graph.executions.start(project.id, "linux", "Optimization environment") { execution, out -> graph.executions.runner.execute(execution, command, graph.executions.directory(project.id), closeInput = true, timeoutSeconds = 30, output = out).exitCode }
            val environment = graph.executions.await(project.id, id)
            assertEquals(environment.toString(), "COMPLETE", environment.getString("state"))
            assertTrue(environment.getString("text").contains("v24.11.0"))
            assertEquals("REVIEW_READABLE", File(graph.executions.directory(project.id), "optimization-review-permissions.txt").readText())
            File(graph.executions.directory(project.id), "optimization-review-permissions.txt").delete()
            evidence.appendText(JSONObject().put("stage", "online").put("pid", host.pid).put("startTicks", host.startTicks).put("instanceId", first.instanceId).put("metrics", metrics).put("memory", memory).put("diagnostic", report).put("environment", environment).put("actual", 42).toString() + "\n")
            graph.bridge.commandSession(host.id, "disconnectDebug")
        }
    }

    @Test fun restorePublishedJava() = runBlocking<Unit>(Dispatchers.IO) {
        ActivityScenario.launch(MainActivity::class.java).use {
            ready()
            val state = JSONObject(fixture.readText()); val project = requireNotNull(graph.store.dao.project(state.getString("projectId")))
            shell("am force-stop $target")
            val ids = state.getJSONArray("plugins")
            for (i in 0 until ids.length()) graph.store.delete(ids.getString(i))
            val enabled = state.getJSONArray("previousEnabled")
            for (i in 0 until enabled.length()) { val plugin = requireNotNull(graph.store.dao.plugin(enabled.getString(i))); graph.store.dao.updatePlugin(plugin.copy(desiredEnabled = true)) }
            if (!state.isNull("previousPublication")) {
                val previous = state.getJSONObject("previousPublication")
                graph.framework.publishJavaSnapshot(previous.getInt("uid").toString() + ":" + target, previous)
                File(graph.filesDir, "java-publications/${project.id}.json").writeText(previous.toString())
            } else graph.javaDeployments.publish(project.id, refresh = true)
            if (!state.isNull("previousNative")) {
                val previous = state.getJSONObject("previousNative")
                graph.framework.publishNativeSnapshot(previous.getInt("uid").toString() + ":" + target, previous)
            }
            shell("nsenter -t 1 -m -- rm -f /data/user/0/$target/files/optimization-review-$target.txt /data/user/0/$target/files/optimization-review-${target}_worker.txt")
            fixture.delete()
            evidence.appendText("{\"stage\":\"restored\"}\n")
        }
    }
}
