package io.github.easylsp.app.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.easylsp.app.MainViewModel
import io.github.easylsp.app.ui.components.*
import io.github.easylsp.app.ui.icons.AppIcons

@Composable fun EnabledPluginsScreen(vm: MainViewModel, edit: (String, String) -> Unit) {
    val plugins by vm.plugins.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val enabled = plugins.filter { it.desiredEnabled }.filter { plugin ->
        val project = projects.firstOrNull { it.id == plugin.projectId }
        listOf(plugin.name, project?.name.orEmpty(), project?.packageName.orEmpty()).any { it.contains(query, true) }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, label = { Text("搜索插件或应用") }, leadingIcon = { Icon(AppIcons.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), singleLine = true)
        if (enabled.isEmpty()) EmptyState(if (query.isEmpty()) "暂无已启用插件" else "没有匹配的插件", if (query.isEmpty()) "在项目中启用插件后，会显示在这里。" else "试试其他插件名或应用名称。")
        LazyColumn(Modifier.weight(1f).testTag("enabled-plugin-list"), contentPadding = PaddingValues(vertical = 12.dp)) {
            items(enabled, key = { it.id }) { plugin ->
                val project = projects.firstOrNull { it.id == plugin.projectId }
                ListItem(
                    leadingContent = { Icon(AppIcons.Extension, null, tint = MaterialTheme.colorScheme.primary) },
                    headlineContent = { Text(plugin.name) },
                    supportingContent = { Text("${project?.name ?: "项目"} · ${if (plugin.runtime == "NATIVE") "Native" else "Java"}\n${project?.packageName.orEmpty()}") },
                    trailingContent = { Icon(AppIcons.ChevronRight, null) },
                    modifier = Modifier.testTag("enabled-plugin-${plugin.id}").clickable { edit(plugin.projectId, plugin.id) }
                )
            }
        }
    }
}
