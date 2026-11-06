package io.github.easylsp.app.feature.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.ui.components.AppCard

/** One explicit setup action requests notification, battery and overlay permissions in order. */
@Composable internal fun BackgroundPermissionsCard(graph: EasylspApp) {
    var revision by remember { mutableIntStateOf(0) }
    var requests by remember { mutableStateOf(emptyList<Intent>()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val battery = remember(revision) { graph.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(graph.packageName) }
    val overlay = remember(revision) { Settings.canDrawOverlays(graph) }
    val notifications = remember(revision) { graph.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled() }
    fun settingsRequests() = buildList {
        val uri = Uri.parse("package:${graph.packageName}")
        if (!notifications && android.os.Build.VERSION.SDK_INT < 33) add(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, graph.packageName))
        if (!battery) add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, uri))
        if (!overlay) add(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri))
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        revision++; requests = requests.drop(1)
    }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        revision++; requests = settingsRequests()
    }
    LaunchedEffect(requests) { requests.firstOrNull()?.let { settings.launch(it) } }
    AppCard {
        Text("后台运行权限", style = MaterialTheme.typography.titleMedium)
        Text("后台运行：${if (battery) "已允许" else "受电池优化限制"} · 悬浮窗：${if (overlay) "已允许" else "未允许"} · 通知：${if (notifications) "已允许" else "未允许"}", style = MaterialTheme.typography.bodySmall)
        Text("任务运行时显示前台通知和可拖动悬浮圈。点悬浮圈返回应用，长按可隐藏。手机管家中的后台活动也需设为允许。", style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = requests.isEmpty() && !(battery && overlay && notifications), onClick = {
            if (android.os.Build.VERSION.SDK_INT >= 33 && graph.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notification.launch(Manifest.permission.POST_NOTIFICATIONS)
            else requests = settingsRequests()
        }) { Text("申请运行权限") }
        Text(io.github.easylsp.app.platform.root.ManufacturerBackgroundSettings.guidance, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { graph.startActivity(io.github.easylsp.app.platform.root.ManufacturerBackgroundSettings.autostart(graph)) }) { Text("厂商自启动 / 后台设置") }
        TextButton(onClick = { settings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${graph.packageName}"))) }) { Text("应用后台设置") }
    }
}
