package io.github.easylsp.app.platform.terminal

import android.content.Context
import io.github.easylsp.app.platform.root.RootCommand
import java.io.File

/** Every privileged Linux process gets a new mount tree before performing its first bind. */
class MountNamespace(private val context: Context) {
    private val launcher: File by lazy {
        val directory = File(context.filesDir, "linux/bin").apply { mkdirs() }
        val temporary = File(directory, "namespace.new")
        context.assets.open("namespace/arm64-v8a/easylsp-namespace").use { input -> temporary.outputStream().use { input.copyTo(it) } }
        check(temporary.setExecutable(true, true))
        File(directory, "namespace").also { check(temporary.renameTo(it)) }
    }
    fun shell(script: String): String = listOf(launcher.path, "/system/bin/sh", "-c", "set -e; $script").joinToString(" ", transform = RootCommand::quote)
}
