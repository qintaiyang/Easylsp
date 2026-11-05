package io.github.easylsp.app.platform.terminal

import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Pipe-based command execution; cancellation kills the shell and its observed descendants. */
class CommandRunner(val shellPath: String = "/system/bin/sh", private val suCommand: String = "su") {
    data class Result(val exitCode: Int, val elapsedMillis: Long)
    private val processes = ConcurrentHashMap<String, Process>()
    fun write(id: String, text: String) { val process = processes[id] ?: error("命令未运行"); synchronized(process) { process.outputStream.write(text.toByteArray()); process.outputStream.flush() } }
    fun closeInput(id: String) { processes[id]?.outputStream?.close() }
    suspend fun terminateRootTree(pid: Long) = withContext(Dispatchers.IO) {
        val listing = ProcessBuilder(suCommand, "-c", "ps -A -o pid,ppid").redirectErrorStream(true).start()
        val rows = listing.inputStream.bufferedReader().use { it.readLines() }
        require(listing.waitFor() == 0) { "无法列出 root 子进程：${rows.joinToString(" ")}" }
        val children = mutableMapOf<Long, MutableList<Long>>()
        rows.drop(1).forEach { line ->
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size == 2) children.getOrPut(fields[1].toLong()) { mutableListOf() }.add(fields[0].toLong())
        }
        fun tree(parent: Long): List<Long> = children[parent].orEmpty().flatMap { tree(it) + it }
        ProcessBuilder(suCommand, "-c", "kill -KILL " + (tree(pid) + pid).joinToString(" ")).start().waitFor(3, TimeUnit.SECONDS)
        Unit
    }
    private fun terminate(process: Process, pidFile: File) {
        // /proc is the real child-process boundary. Descendants may outlive their shell otherwise.
        val parent = if (pidFile.exists()) pidFile.readText().trim().toLong() else -1L
        val children = mutableMapOf<Long, MutableList<Long>>()
        File("/proc").listFiles()?.forEach { entry ->
            if (entry.name.toLongOrNull() != null) try {
                val stat = File(entry, "stat").readText().substringAfterLast(") ").split(' ')
                children.getOrPut(stat[1].toLong()) { mutableListOf() }.add(entry.name.toLong())
            } catch (_: java.io.IOException) { /* process exited during enumeration */ }
        }
        fun descendants(pid: Long): List<Long> = children[pid].orEmpty().flatMap { descendants(it) + it }
        val ids = descendants(parent)
        if (ids.isNotEmpty()) ProcessBuilder(shellPath, "-c", "kill -KILL " + ids.joinToString(" ")).start().waitFor(2, TimeUnit.SECONDS)
        process.destroyForcibly()
    }
    suspend fun execute(id: String, command: List<String>, directory: File, environment: Map<String, String> = emptyMap(), input: String? = null, closeInput: Boolean = false, timeoutSeconds: Int = 120, output: (String) -> Unit): Result = withContext(Dispatchers.IO) {
        require(command.isNotEmpty() && timeoutSeconds in 1..3600)
        val started = System.currentTimeMillis()
        val pidFile = File(directory, ".easylsp-process-${java.util.UUID.randomUUID()}.pid")
        val rootPidFile = File(directory, ".easylsp-root-${java.util.UUID.randomUUID()}.pid")
        val privileged = command.first() == suCommand && command.getOrNull(1) == "-c"
        val actual = if (privileged) listOf(suCommand, "-c", "cd " + io.github.easylsp.app.platform.root.RootCommand.quote(directory.absolutePath) + " && echo \$\$ > " + io.github.easylsp.app.platform.root.RootCommand.quote(rootPidFile.absolutePath) + " && " + command[2]) else command
        val wrapped = listOf(shellPath, "-c", "echo \$\$ > \"\$1\"; shift; exec \"\$@\"", "easylsp", pidFile.absolutePath) + actual
        val process = ProcessBuilder(wrapped).directory(directory).redirectErrorStream(true).apply { environment().putAll(environment) }.start()
        check(processes.putIfAbsent(id, process) == null) { "命令 ID 已运行" }
        val reader = async(Dispatchers.IO) {
            try { process.inputStream.reader().use { stream -> val buffer = CharArray(2048); while (true) { val count = stream.read(buffer); if (count < 0) break; output(String(buffer, 0, count)) } } }
            catch (error: java.io.IOException) { ensureActive(); throw error }
        }
        try {
            if (input != null) withContext(Dispatchers.IO) { write(id, input) }
            if (closeInput) process.outputStream.close()
            val exit = withTimeout(timeoutSeconds * 1000L) {
                val code = runInterruptible(Dispatchers.IO) { process.waitFor() }; reader.await(); code
            }
            Result(exit, System.currentTimeMillis() - started)
        } finally {
            reader.cancel() // Mark cancellation before terminating the process closes its pipe.
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    if (process.isAlive && privileged && rootPidFile.exists()) {
                        val rootPid = rootPidFile.readText().trim().toLong()
                        // The app UID cannot signal root children: use the same granted su manager.
                        terminateRootTree(rootPid)
                    }
                } finally {
                    if (process.isAlive) terminate(process, pidFile)
                    process.outputStream.close(); process.inputStream.close()
                }
            }
            processes.remove(id, process); pidFile.delete(); rootPidFile.delete()
        }
    }
    suspend fun shell(id: String, script: String, directory: File, environment: Map<String,String> = emptyMap(), input: String? = null, timeoutSeconds: Int = 120, output: (String) -> Unit): Result =
        execute(id, listOf(shellPath, "-c", script), directory, environment, input, true, timeoutSeconds, output = output)
}
