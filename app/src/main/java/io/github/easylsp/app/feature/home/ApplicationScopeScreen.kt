package io.github.easylsp.app.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.easylsp.app.MainViewModel
import io.github.easylsp.app.ui.components.*
import io.github.easylsp.app.ui.icons.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable fun ApplicationScopeScreen(vm: MainViewModel) {
    val installed by vm.installed.collectAsStateWithLifecycle()
    val showSystem by vm.showSystem.collectAsStateWithLifecycle()
    val framework by vm.graph.framework.state.collectAsStateWithLifecycle()
    val scope by vm.graph.framework.scope.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    val jobs = rememberCoroutineScope()
    LifecycleResumeEffect(framework.compatible) {
        val refresh = jobs.launch(Dispatchers.IO) {
            if (framework.compatible) vm.graph.framework.refreshScope()
        }
        onPauseOrDispose { refresh.cancel() }
    }
    val apps = installed.filter {
        val selected = it.packageName in scope.packages || it.packageName in scope.pending
        (showSystem || !it.system || selected) && (!selectedOnly || selected) &&
            (it.name.contains(query, true) || it.packageName.contains(query, true))
    }.sortedWith(compareByDescending<MainViewModel.InstalledApp> { it.packageName in scope.packages }.thenBy { it.name.lowercase() })
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, label = { Text("搜索应用名称或包名") }, leadingIcon = { Icon(AppIcons.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("scope-search"), singleLine = true)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selectedOnly, { selectedOnly = !selectedOnly }, label = { Text("已选择") })
            FilterChip(showSystem, { vm.action { vm.graph.settings.showSystem(!showSystem); "筛选已保存" } }, label = { Text("系统应用") })
            Spacer(Modifier.weight(1f))
            IconButton(enabled = !busy, onClick = {
                vm.refreshApps()
                jobs.launch(Dispatchers.IO) { if (framework.compatible) vm.graph.framework.refreshScope() }
            }) { Icon(AppIcons.Refresh, "刷新应用和作用域") }
        }
        if (!framework.compatible) Text("框架未连接，请先在 LSP 中启用 Easylsp。", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error)
        else Text("勾选后向 LSP 请求作用域，批准后生效；取消勾选会移除作用域。", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (scope.error.isNotEmpty()) Text(scope.error, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error)
        if (apps.isEmpty() && !busy) EmptyState("没有匹配的应用", "调整搜索或筛选条件。")
        LazyColumn(Modifier.weight(1f).testTag("scope-app-list"), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(apps, key = { it.packageName }) { app ->
                val checked = app.packageName in scope.packages
                val pending = app.packageName in scope.pending
                val canChange = framework.compatible && scope.loaded && !pending && !busy
                ListItem(
                    headlineContent = { Text(app.name) },
                    supportingContent = { Text(app.packageName + if (pending) "\n等待 LSP 授权" else "") },
                    leadingContent = { AppIcon(app.packageName) },
                    trailingContent = { TriStateCheckbox(if (pending) ToggleableState.Indeterminate else ToggleableState(checked), onClick = null, enabled = canChange) },
                    modifier = Modifier.testTag("scope-app-${app.packageName}").toggleable(checked, enabled = canChange, role = Role.Checkbox) { vm.setApplicationScope(app, it) }
                )
            }
        }
    }
}
