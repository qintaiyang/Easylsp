package io.github.easylsp.app.feature.editor

import io.github.easylsp.app.ui.icons.AppIcons
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.easylsp.app.MainViewModel
import io.github.easylsp.app.data.*
import io.github.easylsp.app.feature.plugins.runtimeLabel
import io.github.easylsp.app.feature.plugins.NativePluginActions
import io.github.easylsp.app.ui.components.*
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

@Composable fun Editor(vm: MainViewModel, project: ProjectEntity, plugin: PluginEntity, state: String, loadedRevision: Long, error: String, busy: Boolean, export: () -> Unit, deleted: () -> Unit, requestBack: () -> Unit, registerBack: ((() -> Unit) -> Unit), logs: () -> Unit, initialSignature: String = "", editorVM: EditorViewModel = viewModel(key = "editor:${plugin.id}")) {
    val document by editorVM.document.collectAsStateWithLifecycle()
    val dirty by editorVM.dirty.collectAsStateWithLifecycle()
    var editor by remember { mutableStateOf<CodeEditor?>(null) }
    var menu by remember { mutableStateOf(false) }
    var panel by rememberSaveable { mutableStateOf("") }
    var leave by remember { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var line by rememberSaveable { mutableStateOf("") }
    var filePath by rememberSaveable { mutableStateOf("") }
    var operation by rememberSaveable { mutableStateOf("file") }
    var collapsedFiles by rememberSaveable { mutableStateOf(listOf<String>()) }
    var selectedPath by rememberSaveable { mutableStateOf(plugin.entry) }
    var signature by rememberSaveable { mutableStateOf(initialSignature) }
    var mode by rememberSaveable { mutableStateOf("before") }
    var templateError by remember { mutableStateOf("") }
    var preview by rememberSaveable { mutableStateOf("") }
    var fileError by remember { mutableStateOf("") }
    fun back() { if (dirty) leave = true else vm.action { editorVM.keepDraft(); requestBack(); "编辑状态已保留" } }
    SideEffect { registerBack(::back) }
    BackHandler { back() }
    LaunchedEffect(plugin.id) { editorVM.load(plugin) }
    val colors = MaterialTheme.colorScheme
    val d = document
    if (d == null) { LoadingState("正在恢复文件和草稿…"); return }
    fun save(after: () -> Unit = {}) { vm.action {
        if (plugin.runtime == "BEANSHELL" && d.process != plugin.processName) {
            val host = vm.graph.bridge.session(project.packageName, plugin.processName.ifEmpty { project.packageName }, project.userId)
            if (host != null) vm.graph.bridge.commandSession(host.id, "unloadPlugin", plugin.id)
        }
        editorVM.save(plugin); after(); if (plugin.runtime == "NATIVE") "已保存新 revision；构建并发布后下次启动生效" else "已保存新 revision；显式加载或重载后生效"
    } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { save() }, enabled = !busy && dirty) { Text("保存") }
            if (project.kind == "INSTALLED" && plugin.runtime == "BEANSHELL") OutlinedButton(onClick = { vm.command(project, if (state == "RUNNING") "reloadPlugin" else "loadPlugin", plugin) }, enabled = !busy && !dirty && plugin.desiredEnabled && state != "OFFLINE" && project.kind == "INSTALLED") { Text(if (state == "RUNNING") "重载" else "加载") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(AppIcons.MoreVert, "编辑器菜单") }
                DropdownMenu(menu, { menu = false }) {
                    listOf("files" to "文件树", "metadata" to "插件元信息", "template" to "Hook 模板", "search" to "搜索 / 替换 / 跳转").forEach { (key, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { panel = key; menu = false }) }
                    if (project.kind == "INSTALLED" && plugin.runtime == "BEANSHELL") DropdownMenuItem(text = { Text("期望${if (plugin.desiredEnabled) "停用" else "启用"}") }, enabled = !dirty && !busy, onClick = { menu = false; vm.setEnabled(project, plugin, !plugin.desiredEnabled) })
                    if (project.kind == "INSTALLED" && plugin.runtime == "BEANSHELL") DropdownMenuItem(text = { Text("卸载运行实例") }, enabled = project.kind == "INSTALLED" && !busy, onClick = { menu = false; vm.command(project, "unloadPlugin", plugin) })
                    DropdownMenuItem(text = { Text("插件日志") }, onClick = { menu = false; logs() })
                    DropdownMenuItem(text = { Text("导出已保存文件") }, enabled = !dirty, onClick = { menu = false; export() })
                    DropdownMenuItem(text = { Text("删除插件") }, onClick = { panel = "delete"; menu = false })
                }
            }
        }
        if (plugin.runtime == "NATIVE") NativePluginActions(vm, project, plugin, !dirty) { path, targetLine ->
            if (path in d.files) { editorVM.open(path); line = targetLine.toString(); panel = "search" }
        }
        else Text("${if (project.kind == "IMPORTED") "静态编辑 · 保存 r${plugin.revision}" else "${if (plugin.desiredEnabled) "期望启用" else "期望停用"} · ${runtimeLabel(state)} · 保存 r${plugin.revision} / 运行 r$loadedRevision"}${if (dirty) " · 草稿有修改" else ""}${if (state == "RUNNING" && plugin.revision != loadedRevision) " · 待重载" else ""}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        if (error.isNotEmpty()) Text(error, color = colors.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp).heightIn(max = 60.dp).verticalScroll(rememberScrollState()))
        if (plugin.runtime == "BEANSHELL" && initialSignature.isNotEmpty()) TextButton(onClick = { signature = initialSignature; panel = "template" }) { Text("从分析签名生成模板") }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = { panel = "files" }) { Icon(AppIcons.FolderOpen, "文件树") }
            d.opened.filter { it in d.files }.forEach { path -> InputChip(d.active == path, { editorVM.open(path) }, label = { Text(path.substringAfterLast('/')) }, trailingIcon = { if (path != plugin.entry && !(path.endsWith('/') && plugin.entry.startsWith(path))) Icon(AppIcons.Close, "关闭文件", Modifier.size(16.dp).clickable { editorVM.close(path) }) }) }
        }
        key(d.active) {
            AndroidView(factory = { context ->
                CodeEditor(context).apply {
                    setTextSize(14f); typefaceText = android.graphics.Typeface.MONOSPACE; isLineNumberEnabled = true; tabWidth = 4
                    setEditorLanguage(ScriptLanguage(d.active.substringAfterLast('.')))
                    setText(d.files.getValue(d.active))
                    subscribeEvent(ContentChangeEvent::class.java) { _, _ -> editorVM.change { current -> current.copy(files = current.files + (current.active to text.toString())) } }
                    editor = this
                }
            }, modifier = Modifier.weight(1f).fillMaxWidth(), onRelease = { it.release(); if (editor === it) editor = null }, update = { view ->
                view.colorScheme = EditorColorScheme().apply {
                    setColor(EditorColorScheme.WHOLE_BACKGROUND, colors.surface.toArgb()); setColor(EditorColorScheme.TEXT_NORMAL, colors.onSurface.toArgb())
                    setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, colors.surfaceContainer.toArgb()); setColor(EditorColorScheme.LINE_NUMBER, colors.onSurfaceVariant.toArgb())
                    setColor(EditorColorScheme.CURRENT_LINE, colors.surfaceContainer.toArgb()); setColor(EditorColorScheme.SELECTION_INSERT, colors.primary.toArgb())
                    setColor(EditorColorScheme.KEYWORD, colors.primary.toArgb()); setColor(EditorColorScheme.LITERAL, colors.tertiary.toArgb()); setColor(EditorColorScheme.COMMENT, colors.onSurfaceVariant.toArgb())
                }
            })
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton(onClick = { editor?.undo() }) { Icon(AppIcons.Undo, "撤销") }
            IconButton(onClick = { editor?.redo() }) { Icon(AppIcons.Redo, "重做") }
            IconButton(onClick = { editor?.indentSelection() }) { Icon(AppIcons.FormatIndentIncrease, "缩进") }
            IconButton(onClick = { editor?.unindentSelection() }) { Icon(AppIcons.FormatIndentDecrease, "减少缩进") }
            listOf("{", "}", "(", ")", ";", "\"", "=", ".").forEach { symbol -> TextButton(onClick = { editor?.insertText(symbol, symbol.length) }, contentPadding = PaddingValues(6.dp), modifier = Modifier.widthIn(min = 32.dp)) { Text(symbol) } }
        }
    }
    if (panel.isNotEmpty()) AlertDialog(onDismissRequest = { panel = "" }, title = { Text(when (panel) { "files" -> "插件文件"; "metadata" -> "插件元信息"; "template" -> "Hook 模板"; "delete" -> "删除插件？"; else -> "搜索与定位" }) }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (panel) {
                "files" -> {
                    Text("${plugin.entry} 是运行入口；元信息由表单管理。附属文本随 revision 保存。", style = MaterialTheme.typography.bodySmall)
                    val paths = (d.files.keys + d.files.keys.flatMap { path -> path.split('/').dropLast(1).runningFold("") { parent, name -> if (parent.isEmpty()) name else "$parent/$name" }.drop(1).map { "$it/" } }).distinct().sorted()
                    paths.filter { path -> collapsedFiles.none { path != it && path.startsWith(it) } }.forEach { path ->
                        Row { TextButton(onClick = { selectedPath = path; filePath = path; if (path.endsWith('/')) collapsedFiles = if (path in collapsedFiles) collapsedFiles - path else collapsedFiles + path else { editorVM.open(path); panel = "" } }, modifier = Modifier.weight(1f).padding(start = (path.removeSuffix("/").count { it == '/' } * 8).dp)) { Text((if (path.endsWith('/')) (if (path in collapsedFiles) "+ " else "− ") else "") + path.removeSuffix("/").substringAfterLast('/'), maxLines = 2) }
                            if (path != plugin.entry && !(path.endsWith('/') && plugin.entry.startsWith(path))) { TextButton(onClick = { selectedPath = path; filePath = path; operation = "rename" }) { Text("改名") }; IconButton(onClick = { editorVM.remove(path) }) { Icon(AppIcons.DeleteOutline, "删除 $path") } }
                        }
                    }
                    Row { listOf("file" to "新文件", "folder" to "新文件夹").forEach { (id, label) -> FilterChip(operation == id, { operation = id; filePath = "" }, label = { Text(label) }) } }
                    OutlinedTextField(filePath, { filePath = it }, label = { Text(if (operation == "rename") "新路径（$selectedPath）" else "相对路径") })
                    TextButton(onClick = {
                        try { if (operation == "rename") editorVM.rename(selectedPath, filePath) else editorVM.add(filePath, operation == "folder"); filePath = ""; fileError = "" } catch (e: IllegalArgumentException) { fileError = e.message.orEmpty() }
                    }) { Text(if (operation == "rename") "重命名" else "添加") }
                    if (fileError.isNotEmpty()) Text(fileError, color = colors.error)
                }
                "metadata" -> {
                    OutlinedTextField(d.name, { value -> editorVM.change { it.copy(name = value) } }, label = { Text("名称") })
                    OutlinedTextField(d.author, { value -> editorVM.change { it.copy(author = value) } }, label = { Text("作者") })
                    OutlinedTextField(d.version, { value -> editorVM.change { it.copy(version = value) } }, label = { Text("版本") })
                    OutlinedTextField(d.description, { value -> editorVM.change { it.copy(description = value) } }, label = { Text("说明") })
                    OutlinedTextField(d.process, { value -> editorVM.change { it.copy(process = value) } }, label = { Text("进程名（空值为主进程）") })
                }
                "template" -> if (plugin.runtime == "NATIVE") {
                    Text("C SDK 片段；函数签名和模块名称需按目标分析结果填写。")
                    listOf("host->log(ctx->instance, \"message\");", "host->hit(ctx->instance, hook_id);", "int saved_errno = errno;\n/* replacement logic */\nerrno = saved_errno;").forEach { snippet ->
                        TextButton(onClick = { editor?.insertText(snippet, snippet.length); panel = "" }) { Text(snippet) }
                    }
                } else {
                    OutlinedTextField(signature, { signature = it; preview = "" }, label = { Text("完整 DEX 方法签名") })
                    Row { listOf("before", "after", "replace").forEach { item -> FilterChip(mode == item, { mode = item; preview = "" }, label = { Text(item) }) } }
                    TextButton(onClick = { try { preview = HookTemplate.generate(HookTemplate.parse(signature), mode); templateError = "" } catch (e: IllegalArgumentException) { templateError = e.message.orEmpty() } catch (e: IllegalStateException) { templateError = e.message.orEmpty() } }) { Text("解析并预览") }
                    if (templateError.isNotEmpty()) Text(templateError, color = colors.error)
                    if (preview.isNotEmpty()) { Text(preview, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.bodySmall); Button(onClick = { editor?.insertText(preview, preview.length); panel = "" }) { Text("插入当前光标") } }
                    Text("将 Hook 注册代码插入 onLoad() 内。补全范围为词法和平台片段。", style = MaterialTheme.typography.bodySmall)
                }
                "delete" -> Text(if (plugin.runtime == "NATIVE") "先撤销发布，再删除本地文件及草稿。当前进程仍保留已加载代码直到退出。" else "将卸载此插件的运行实例并删除已保存文件及草稿。")
                else -> {
                    OutlinedTextField(search, { search = it; if (it.isNotEmpty()) editor?.searcher?.search(it, EditorSearcher.SearchOptions(true, false)) else editor?.searcher?.stopSearch() }, label = { Text("查找（普通文本）") })
                    Row { TextButton(onClick = { editor?.searcher?.gotoPrevious() }) { Text("上一个") }; TextButton(onClick = { editor?.searcher?.gotoNext() }) { Text("下一个") } }
                    OutlinedTextField(replacement, { replacement = it }, label = { Text("替换为") })
                    Row { TextButton(enabled = search.isNotEmpty(), onClick = { editor?.searcher?.replaceCurrentMatch(replacement) }) { Text("替换") }; TextButton(enabled = search.isNotEmpty(), onClick = { editor?.searcher?.replaceAll(replacement) }) { Text("全部替换") } }
                    OutlinedTextField(line, { line = it }, label = { Text("跳转行号") })
                    TextButton(onClick = { line.toIntOrNull()?.takeIf { it in 1..d.files.getValue(d.active).lines().size }?.let { editor?.setSelection(it - 1, 0); panel = "" } }) { Text("跳转") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { if (panel == "delete") vm.delete(project, plugin, deleted); panel = "" }) { Text(if (panel == "delete") "删除" else "完成") } })
    if (leave) AlertDialog(onDismissRequest = { leave = false }, title = { Text("文件有未保存修改") }, text = { Text("草稿已保存在本机。保存会生成新 revision，运行实例在显式重载前继续使用原版本。") }, confirmButton = { TextButton(onClick = { leave = false; save(requestBack) }) { Text("保存并离开") } }, dismissButton = { Row { TextButton(onClick = { vm.action { editorVM.keepDraft(); leave = false; requestBack(); "草稿已保留" } }) { Text("保留草稿") }; TextButton(onClick = { vm.action { editorVM.discard(); leave = false; requestBack(); "已放弃修改" } }) { Text("放弃修改") } } })
}
