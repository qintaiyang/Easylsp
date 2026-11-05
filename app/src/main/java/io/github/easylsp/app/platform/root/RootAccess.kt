package io.github.easylsp.app.platform.root

import io.github.easylsp.app.platform.terminal.CommandRunner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Manager-neutral su protocol shared by KernelSU, Magisk, APatch and compatible managers. */
data class RootState(val status: String = "CHECKING", val granted: Boolean = false, val detail: String = "正在检测/申请 su 授权")
object RootCommand {
    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    fun wrap(command: List<String>, root: Boolean, su: String = "su"): List<String> = if (root) listOf(su, "-c", "umask 0007; " + command.joinToString(" ", transform = ::quote)) else command
}
class RootAccess(private val directory: File, private val runner: CommandRunner = CommandRunner(), val su: String = "su") {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preference = File(directory, "root-preference.json")
    val preferred = MutableStateFlow(if (preference.exists()) JSONObject(preference.readText()).getBoolean("preferred") else true)
    val state = MutableStateFlow(if (preferred.value) RootState() else RootState("DISABLED", detail = "已关闭优先 root"))
    @Volatile private var probe: Deferred<RootState>? = null
    @Synchronized fun refresh(): Deferred<RootState> {
        probe?.takeIf { it.isActive }?.let { return it }
        state.value = RootState()
        return scope.async {
            val output = StringBuilder()
            val value = try {
                val result = runner.execute(UUID.randomUUID().toString(), listOf(su, "-c", "id"), directory, closeInput = true, timeoutSeconds = 30) { output.append(it) }
                val granted = result.exitCode == 0 && Regex("(?:^|\\s)uid=0\\b").containsMatchIn(output)
                RootState(if (granted) "GRANTED" else "DENIED", granted, output.toString().trim().ifEmpty { "su exit=${result.exitCode}" })
            } catch (error: TimeoutCancellationException) { RootState("TIMEOUT", detail = "root 授权等待超时，可在管理器授权后重新检测") }
            catch (error: Exception) { RootState("UNAVAILABLE", detail = error.message ?: error.toString()) }
            state.value = value; value
        }.also { probe = it }
    }
    fun prefer(value: Boolean) { preferred.value = value; preference.writeText(JSONObject().put("preferred", value).toString()) }
    suspend fun useRoot(): Boolean = preferred.value && (probe ?: refresh()).await().granted
    suspend fun wrap(command: List<String>): List<String> = RootCommand.wrap(command, useRoot(), su)
}
