package io.github.easylsp.app.feature.p3

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.easylsp.app.MainViewModel
import io.github.easylsp.app.ui.icons.AppIcons
import io.github.easylsp.app.util.readUpTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable fun WorkspaceFilesScreen(vm: MainViewModel, workspaceId: String, shared: Boolean, openOutput: (String, String, String) -> Unit) {
    var relative by rememberSaveable(workspaceId) { mutableStateOf(".") }
    var refresh by remember { mutableIntStateOf(0) }
    var directory by remember(workspaceId) { mutableStateOf<File?>(null) }
    var entries by remember(workspaceId) { mutableStateOf<List<File>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var exportPath by rememberSaveable { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.action {
            withContext(Dispatchers.IO) {
                File(requireNotNull(exportPath)).inputStream().use { input -> requireNotNull(vm.graph.contentResolver.openOutputStream(uri)).use { input.copyTo(it) } }
            }
            "文件已导出"
        }
    }
    LaunchedEffect(workspaceId, relative, refresh) {
        loading = true; error = ""; entries = emptyList()
        try {
            val result = withContext(Dispatchers.IO) {
                val folder = vm.graph.executions.directory(workspaceId, relative)
                folder to requireNotNull(folder.listFiles()) { "无法读取目录：${folder.path}" }
                    .filter { it.name != ".workspace.json" }.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })
            }
            directory = result.first; entries = result.second
        } catch (failure: kotlinx.coroutines.CancellationException) { throw failure }
        catch (failure: Exception) { error = failure.message.orEmpty() }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(if (shared) "项目工作区" else "会话工作区", style = MaterialTheme.typography.titleMedium)
        Text(if (shared) "同一项目的所有会话共享这里的文件；删除会话会保留项目文件。" else "此独立聊天使用自己的工作区。", style = MaterialTheme.typography.bodySmall)
        directory?.let { SelectionContainer { Text(it.path, style = MaterialTheme.typography.labelSmall) } }
        Row {
            TextButton(enabled = relative != ".", onClick = { relative = File(relative).parent ?: "." }) { Text("上一级") }
            TextButton(onClick = { relative = "." }) { Text("根目录") }
            TextButton(onClick = { refresh++ }) { Text("刷新") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        if (!loading && error.isEmpty() && entries.isEmpty()) Text("工作区还是空的。AI 或终端生成的文件会出现在这里。")
        LazyColumn(Modifier.weight(1f)) {
            items(entries, key = { it.path }) { file ->
                ListItem(headlineContent = { Text(file.name) }, supportingContent = { Text(if (file.isDirectory) "文件夹" else "${file.length()} 字节") },
                    leadingContent = { if (file.isDirectory) Icon(AppIcons.FolderOpen, null) },
                    trailingContent = { if (file.isFile) TextButton(onClick = { exportPath = file.path; export.launch(file.name) }) { Text("导出") } },
                    modifier = Modifier.clickable {
                        if (file.isDirectory) relative = if (relative == ".") file.name else "$relative/${file.name}"
                        else vm.action {
                            val text = withContext(Dispatchers.IO) {
                                val bytes = file.inputStream().use { it.readUpTo(512 * 1024 + 1) }
                                require(bytes.size <= 512 * 1024) { "文件较大，请导出后打开" }
                                require(0.toByte() !in bytes) { "这是二进制文件，请导出后打开" }
                                bytes.toString(Charsets.UTF_8)
                            }
                            openOutput(file.name, text, file.extension.ifEmpty { "text" }); "文件已打开"
                        }
                    })
            }
        }
    }
}
