package io.github.easylsp.app

import io.github.easylsp.app.platform.terminal.*
import io.github.easylsp.app.platform.skills.SkillScript
import io.github.easylsp.app.platform.frida.FridaCommand
import io.github.easylsp.app.platform.agents.*
import io.github.easylsp.app.platform.tools.*
import io.github.easylsp.app.data.ProviderEntity
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

class P3ExecutionTest {
    private fun root() = Files.createTempDirectory("easylsp-p3-").toFile()
    @Test fun actualShellStreamsAcceptsStdinAndReportsExit() = runBlocking {
        val dir = root(); val runner = CommandRunner("/bin/sh"); val ready = CompletableDeferred<Unit>(); val output = StringBuffer()
        try {
            val task = async { runner.execute("stdin", listOf("/bin/sh", "-c", "printf 'ready\\n'; read answer; printf 'received:%s\\n' \"\$answer\"; exit 7"), dir, timeoutSeconds = 5) { output.append(it); ready.complete(Unit) } }
            withTimeout(3000) { ready.await() }; runner.write("stdin", "hello\n"); runner.closeInput("stdin")
            assertEquals(7, task.await().exitCode); assertTrue(output.toString().contains("received:hello"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun timeoutKillsRunningShellAndCancellationStopsDescendants() = runBlocking {
        val dir = root(); val runner = CommandRunner("/bin/sh")
        try {
            try { runner.shell("timeout", "sleep 30", dir, timeoutSeconds = 1) {}; fail("must time out") } catch (_: TimeoutCancellationException) {}
            val ready = CompletableDeferred<Unit>()
            val job = launch { runner.shell("cancel", "sleep 30 & echo \$! > child.pid; echo ready; wait", dir, timeoutSeconds = 30) { ready.complete(Unit) } }
            withTimeout(3000) { ready.await() }
            val pid = File(dir, "child.pid").readText().trim()
            withTimeout(3000) { job.cancelAndJoin() }
            val stat = File("/proc/$pid/stat")
            val state = try { String(Files.readAllBytes(stat.toPath())) } catch (_: java.nio.file.NoSuchFileException) { null }
            assertTrue(state == null || state.substringAfterLast(") ").startsWith("Z "))
        } finally { dir.deleteRecursively() }
    }
    @Test fun executionStorePersistsPagesOwnershipAndInterruptedState() {
        val dir = root(); val project = UUID.randomUUID().toString()
        val store = ExecutionStore(File(dir, "executions"))
        try {
            // Native workspace permissions are exercised by the Android/Linux device test.
            val id = UUID.randomUUID().toString()
            store.save(ExecutionRecord(id, project, "terminal", "printf abcdef; exit 4", "ERROR", exitCode = 4))
            store.append(id, "abcdef")
            val page = store.page(project, id, 0, 12000)
            assertEquals("ERROR", page.getString("state")); assertEquals(4, page.getInt("exitCode"))
            assertEquals("abc", store.page(project, id, 0, 3).getString("text"))
            assertEquals(3, store.page(project, id, 0, 3).getInt("nextOffset"))
            try { store.page("other", id, 0, 10); fail("ownership") } catch (_: IllegalStateException) {}
            try { store.page(project, id, 0, 18000); fail("page limit") } catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("12000")) }
            val running = UUID.randomUUID().toString(); store.save(ExecutionRecord(running, project, "agent", "unfinished", "RUNNING"))
            val restored = ExecutionStore(File(dir, "executions"))
            assertEquals("INTERRUPTED", restored.records.value.first { it.id == running }.state)
            assertEquals("def", restored.page(project, id, 3, 3).getString("text"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun actualBeanShellSkillReceivesArgumentsAndWorkspaceAndStreamsOutput() {
        val output = StringBuilder()
        val result = SkillScript.run("print(workspace); return arguments.getString(\"text\");", JSONObject().put("text", "真实脚本结果"), "/project", output::append)
        assertEquals("真实脚本结果", result.getString("result")); assertTrue(output.contains("/project"))
    }
    @Test fun fridaArgumentsKeepSourceAndPidAndQuoteRootCommand() = runBlocking {
        val dir = root()
        try {
            val script = File(dir, "script's name.js").apply { writeText("console.log(Process.id)") }
            val fixture = File(dir, "fixture injector").apply { writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\"\n"); setExecutable(true) }
            val args = FridaCommand.arguments(fixture.absolutePath, 123, script, false)
            val output = StringBuilder(); CommandRunner("/bin/sh").execute("frida", args, dir, closeInput = true, output = output::append)
            assertEquals("-p\n123\n-s\n${script.absolutePath}\n", output.toString())
            val rootCommand = FridaCommand.arguments(fixture.absolutePath, 123, script, true)
            val quoted = StringBuilder(); CommandRunner("/bin/sh").shell("quote", rootCommand.last(), dir, output = quoted::append)
            assertEquals(output.toString(), quoted.toString())
        } finally { dir.deleteRecursively() }
    }
    @Test fun delegatedToolsUseTheSameUserSelectedMode() {
        fun tool(name: String, writes: Boolean = false) = ToolDefinition(name, name, JSONObject(), writes = writes, invoke = { JSONObject() })
        val catalog = listOf(tool("get_project_info"), tool("read_manifest"), tool("edit_plugin", true), tool("spawn_agent"), tool("run_command"), tool("run_skill"))
        assertEquals(catalog, io.github.easylsp.app.platform.ai.RunModes.filter("normal", catalog))
        assertFalse(io.github.easylsp.app.platform.ai.RunModes.filter("plan", catalog).any { it.name == "edit_plugin" })
    }
}
