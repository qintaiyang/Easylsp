package io.github.easylsp.app

import io.github.easylsp.app.platform.root.*
import io.github.easylsp.app.platform.terminal.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RootAccessTest {
    private fun su(dir: File, body: String): File = File(dir, "su").apply { writeText("#!/bin/sh\n$body\n"); setExecutable(true) }
    @Test fun genericSuGrantRoutesCommandsPreservesArgumentsAndPreference() = runBlocking {
        val dir = Files.createTempDirectory("root-grant-").toFile()
        try {
            val binary = su(dir, "case \"\$2\" in *\"&& id\") echo 'uid=0(root) gid=0(root)';; *) exec /bin/sh -c \"\$2\";; esac")
            val runner = CommandRunner("/bin/sh", binary.path)
            val root = RootAccess(dir, runner, binary.path)
            assertTrue(root.refresh().await().granted)
            val command = root.wrap(listOf("/bin/sh", "-c", "printf '%s' \"\$1\"", "test", "a 'quoted' ; value"))
            val output = StringBuilder(); runner.execute("root", command, dir, closeInput = true, output = output::append)
            assertEquals("a 'quoted' ; value", output.toString())
            root.prefer(false); assertFalse(root.useRoot())
            assertEquals(listOf("id"), root.wrap(listOf("id")))
            assertFalse(RootAccess(dir, runner, binary.path).preferred.value)
        } finally { dir.deleteRecursively() }
    }
    @Test fun privilegedCompoundScriptRunsEveryStatement() = runBlocking {
        val dir = Files.createTempDirectory("root-compound-").toFile()
        try {
            val binary = su(dir, "exec /bin/sh -c \"\$2\"")
            val runner = CommandRunner("/bin/sh", binary.path)
            val output = StringBuilder()
            val result = runner.execute("compound", listOf(binary.path, "-c", "set -e; printf first; printf second > actual.txt; cat actual.txt"), dir, closeInput = true, output = output::append)
            assertEquals(0, result.exitCode); assertEquals("firstsecond", output.toString()); assertEquals("second", File(dir, "actual.txt").readText())
        } finally { dir.deleteRecursively() }
    }
    @Test fun deniedSuUsesAppUidWithoutRetryingFailedOperations() = runBlocking {
        val dir = Files.createTempDirectory("root-denied-").toFile()
        try {
            val binary = su(dir, "echo denied >&2; exit 1")
            val root = RootAccess(dir, CommandRunner("/bin/sh", binary.path), binary.path)
            assertFalse(root.useRoot()); assertEquals("DENIED", root.state.value.status)
            assertEquals(listOf("id"), root.wrap(listOf("id")))
        } finally { dir.deleteRecursively() }
    }
    @Test fun rootCommandCancellationStopsItsDescendants() = runBlocking {
        val dir = Files.createTempDirectory("root-cancel-").toFile()
        try {
            val binary = su(dir, "exec /bin/sh -c \"\$2\"")
            val runner = CommandRunner("/bin/sh", binary.path)
            val ready = CompletableDeferred<Unit>()
            val job = launch { runner.execute("cancel", RootCommand.wrap(listOf("/bin/sh", "-c", "sleep 30 & echo \$! > child; echo ready; wait"), true, binary.path), dir) { ready.complete(Unit) } }
            withTimeout(3000) { ready.await() }; val pid = File(dir, "child").readText().trim()
            withTimeout(5000) { job.cancelAndJoin() }
            val stat = File("/proc/$pid/stat")
            assertTrue(!stat.exists() || stat.readText().substringAfterLast(") ").startsWith("Z "))
        } finally { dir.deleteRecursively() }
    }
}
