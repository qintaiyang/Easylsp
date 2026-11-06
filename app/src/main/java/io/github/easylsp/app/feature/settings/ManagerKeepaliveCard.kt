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
        Text("root 授权后自动配置。降低管理器被回收的概率，解除进程冻结；开机自动启动保活服务。关闭后恢复本次服务修改的进程设置。", style = MaterialTheme.typography.bodySmall)
        ListItem(headlineContent = { Text("启用 UID0 保活") }, trailingContent = {
            Switch(keeper.enabled, { enabled -> vm.action { vm.graph.managerKeepalive.setEnabled(enabled).detail } }, enabled = root.granted && !busy)
        })
        TextButton(enabled = root.granted && !busy, onClick = { vm.action { vm.graph.managerKeepalive.reconcile().detail } }) { Text("刷新 / 修复保活") }
    }
}
