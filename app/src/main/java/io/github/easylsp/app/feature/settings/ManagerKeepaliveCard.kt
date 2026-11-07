package io.github.easylsp.app.feature.settings

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.easylsp.app.MainViewModel
import io.github.easylsp.app.ui.components.AppCard

@Composable internal fun ManagerKeepaliveCard(vm: MainViewModel) {
    val keeper by vm.graph.managerKeepalive.state.collectAsStateWithLifecycle()
    val root by vm.graph.root.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    AppCard {
        Text("UID0 管理器保活", style = MaterialTheme.typography.titleMedium)
        Text(keeper.detail, style = MaterialTheme.typography.bodySmall)
        Text("开发任务运行时按需保护管理进程，任务结束后恢复设置并退出。日常使用已发布插件无需管理器常驻。", style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = root.granted && !busy, onClick = { vm.action { vm.graph.managerKeepalive.reconcile().detail } }) { Text("刷新 / 修复保活") }
    }
}
