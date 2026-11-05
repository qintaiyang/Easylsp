package io.github.easylsp.app.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.easylsp.app.MainViewModel
import io.github.easylsp.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.*
import java.util.UUID

@Composable fun KnowledgeScreen(vm: MainViewModel, promptsOnly: Boolean = false, skillsOnly: Boolean = false) {
    val graph = vm.graph; val dao = graph.store.workflows
    val prompts by dao.observePrompts().collectAsStateWithLifecycle(emptyList())
    val skills by dao.observeSkills().collectAsStateWithLifecycle(emptyList())
    val projects by vm.projects.collectAsStateWithLifecycle()
    var edit by remember { mutableStateOf<PromptEntity?>(null) }
    var github by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<SkillEntity?>(null) }
    var exportSkill by remember { mutableStateOf<SkillEntity?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.action {
        val skill = withContext(Dispatchers.IO) { graph.skillPackages.import(requireNotNull(graph.contentResolver.openInputStream(uri))) }; "已导入 ${skill.name}"
    } }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.action {
        withContext(Dispatchers.IO) { graph.skillPackages.export(requireNotNull(exportSkill), requireNotNull(graph.contentResolver.openOutputStream(uri))) }; "Skill 已导出"
    } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!skillsOnly) {
            Text("Prompt 预设", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { edit = PromptEntity(UUID.randomUUID().toString(), "新预设", "") }) { Text("新建 Prompt") }
            prompts.forEach { prompt -> ListItem(headlineContent = { Text(prompt.name) }, modifier = Modifier.clickable { edit = prompt }) }
        }
        if (!promptsOnly) {
            Text("Skills", style = MaterialTheme.typography.titleMedium)
            Text("标准 SKILL.md、脚本和参考资料，按需读取；可选择全局或项目作用域。", style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("导入 ZIP") }
                TextButton(onClick = { vm.action { selected = withContext(Dispatchers.IO) { graph.skillPackages.create(null, JSONObject().put("SKILL.md", "---\nname: new-skill\ndescription: 说明此 Skill 的用途\n---\n\n在这里编写指导。\n")) }; "Skill 已创建" } }) { Text("新建目录包") }
            }
            Field("GitHub 仓库或 tree URL", github) { github = it }
            TextButton(enabled = github.isNotBlank(), onClick = { vm.action { val item = withContext(Dispatchers.IO) { graph.skillPackages.github(github) }; "已导入 ${item.name}" } }) { Text("从 GitHub 导入") }
            skills.filter { it.packagePath.isNotEmpty() }.forEach { item ->
                ListItem(headlineContent = { Text(item.name) }, supportingContent = { Text("${if (item.projectId == null) "全局" else "项目"} · r${item.revision}\n${item.description}") }, modifier = Modifier.clickable { selected = item },
                    trailingContent = { Switch(item.enabled, { enabled -> vm.action { withContext(Dispatchers.IO) { graph.skillPackages.save(item.copy(enabled = enabled)) }; "Skill 状态已保存" } }) })
            }
        }
    }
    edit?.let { prompt ->
        var name by remember(prompt.id) { mutableStateOf(prompt.name) }; var text by remember(prompt.id) { mutableStateOf(prompt.text) }
        EditorDialog("Prompt 预设", { edit = null }, { vm.action { require(name.isNotBlank()); withContext(Dispatchers.IO) { dao.savePrompt(prompt.copy(name = name, text = text)) }; edit = null; "预设已保存" } }) {
            Field("名称", name) { name = it }; OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().height(230.dp))
            TextButton(onClick = { vm.action { withContext(Dispatchers.IO) { dao.deletePrompt(prompt.id) }; edit = null; "预设已删除" } }) { Text("删除") }
        }
    }
    selected?.let { skill ->
        var path by remember(skill.id) { mutableStateOf("SKILL.md") }
        var text by remember(skill.id) { mutableStateOf("") }
        var projectId by remember(skill.id) { mutableStateOf(skill.projectId) }
        var resources by remember(skill.id) { mutableStateOf(emptyList<String>()) }
        LaunchedEffect(skill.id, path) { withContext(Dispatchers.IO) { resources = graph.skillPackages.resources(skill); text = if (path in resources) graph.skillPackages.read(skill, path) else "" } }
        EditorDialog("${skill.name} · 资源", { selected = null }, { vm.action {
            withContext(Dispatchers.IO) { val row = graph.skillPackages.update(skill.projectId, skill.id, JSONObject().put(path, text), JSONArray()); graph.skillPackages.save(row.copy(projectId = projectId)) }
            selected = null; "资源与元数据已保存"
        } }) {
            Row(Modifier.horizontalScroll(rememberScrollState())) { FilterChip(projectId == null, { projectId = null }, label = { Text("全局") }); projects.forEach { project -> FilterChip(projectId == project.id, { projectId = project.id }, label = { Text(project.name) }) } }
            Row(Modifier.horizontalScroll(rememberScrollState())) { resources.forEach { resource -> TextButton(onClick = { path = resource }) { Text(resource) } } }
            Field("相对路径（可新建资源）", path) { path = it }
            OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().height(260.dp))
            Row {
                TextButton(onClick = { exportSkill = skill; exporter.launch("${skill.name}.zip") }) { Text("导出") }
                if (path != "SKILL.md") TextButton(onClick = { vm.action { withContext(Dispatchers.IO) { graph.skillPackages.update(skill.projectId, skill.id, JSONObject(), JSONArray().put(path)) }; path = "SKILL.md"; "资源已删除" } }) { Text("删除资源") }
                TextButton(onClick = { vm.action { withContext(Dispatchers.IO) { dao.deleteSkill(skill.id); java.io.File(skill.packagePath).deleteRecursively() }; selected = null; "Skill 已删除" } }) { Text("删除 Skill") }
            }
        }
    }
}
