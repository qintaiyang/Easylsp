package io.github.easylsp.app.feature.home

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.easylsp.app.BuildConfig
import io.github.easylsp.app.MainViewModel
import io.github.easylsp.app.platform.nativeplugins.nativeHostProblem
import io.github.easylsp.app.ui.components.AppCard
import io.github.easylsp.app.ui.icons.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable fun HomeScreen(vm: MainViewModel, applications: () -> Unit, enabledPlugins: () -> Unit, settings: (String) -> Unit) {
    val nativeHost by vm.graph.nativeHost.state.collectAsStateWithLifecycle()
    val framework by vm.graph.framework.state.collectAsStateWithLifecycle()
    val root by vm.graph.root.state.collectAsStateWithLifecycle()
    val scope by vm.graph.framework.scope.collectAsStateWithLifecycle()
    val plugins by vm.plugins.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val jobs = rememberCoroutineScope()
    LifecycleResumeEffect(root.granted, framework.compatible) {
        val refresh = jobs.launch(Dispatchers.IO) {
            if (root.granted) vm.graph.nativeHost.refresh()
            if (framework.compatible) vm.graph.framework.refreshScope()
        }
        onPauseOrDispose { refresh.cancel() }
    }
    val javaReady = framework.compatible
    val nativeReady = nativeHostProblem(nativeHost, root.granted) == null
    val enabled = plugins.filter { it.desiredEnabled }
    val nativeProjects = enabled.filter { it.runtime == "NATIVE" }.map { it.projectId }.toSet()
    val needsAttention = enabled.any { it.runtime == "BEANSHELL" } && !javaReady || projects.any {
        it.id in nativeProjects && !(if (it.nativeBackend == "LIBXPOSED") javaReady else nativeReady)
    }
    val working = javaReady || nativeReady
    val status = when { needsAttention && working -> "部分工作"; needsAttention -> "需要处理"; working -> "工作中"; else -> "未工作" }
    val detail = when {
        needsAttention -> "部分插件的运行环境未就绪"
        javaReady && nativeReady -> "Java 与 Native 已就绪"
        javaReady -> "${framework.frameworkName} 已连接"
        nativeReady -> "Native 引擎已就绪"
        else -> "点击查看执行环境"
    }
    val engine = nativeHost.optJSONObject("runtime")?.optString("runtimeVersion").orEmpty().ifEmpty {
        nativeHost.optString("installedRuntime", "未连接")
    }
    LazyColumn(Modifier.fillMaxSize().testTag("home-overview"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Card(onClick = { settings("environment") }, modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = if (needsAttention || !working) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primaryContainer),
                shape = MaterialTheme.shapes.extraLarge) {
                Row(Modifier.padding(24.dp).heightIn(min = 88.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Icon(if (working && !needsAttention) AppIcons.VerifiedUser else AppIcons.Info, null, Modifier.size(48.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(status, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("home-work-status"))
                        Text(detail, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OverviewEntry("应用", if (scope.loaded) scope.packages.count { it != vm.graph.packageName }.toString() else "—", AppIcons.Apps, Modifier.weight(1f).testTag("home-applications"), applications)
                OverviewEntry("插件", enabled.size.toString(), AppIcons.Extension, Modifier.weight(1f).testTag("home-plugins"), enabledPlugins)
            }
        }
        item {
            AppCard {
                OverviewInfo("管理器版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                OverviewInfo("引擎版本", engine)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                OverviewInfo("设备型号", "${Build.MANUFACTURER} ${Build.MODEL}")
            }
        }
    }
}

@Composable private fun OverviewEntry(label: String, count: String, icon: ImageVector, modifier: Modifier, open: () -> Unit) {
    Card(onClick = open, modifier = modifier, shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Icon(AppIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            Text(count, style = MaterialTheme.typography.headlineLarge)
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable private fun OverviewInfo(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
