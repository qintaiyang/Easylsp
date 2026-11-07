package io.github.easylsp.app.feature.settings

import io.github.easylsp.app.ui.icons.AppIcons
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import io.github.easylsp.app.*
import io.github.easylsp.app.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun SettingsScreen(open: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppCard {
            listOf(Triple("appearance", "外观", AppIcons.Palette), Triple("models", "模型", AppIcons.AutoAwesome), Triple("extensions", "扩展与工具", AppIcons.Extension), Triple("memory", "记忆与规则", AppIcons.AutoAwesome), Triple("environment", "执行环境", AppIcons.Terminal), Triple("storage", "存储", AppIcons.Storage), Triple("about", "关于", AppIcons.Info)).forEach { (id, label, icon) ->
                ListItem(headlineContent = { Text(label) }, leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }, trailingContent = { Icon(AppIcons.ChevronRight, null) }, modifier = Modifier.clickable { open(id) }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
            }
        }
        Text("Easylsp ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun SettingsDetailScreen(vm: MainViewModel, category: String, openOutput: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    val appearance by vm.graph.settings.appearance.collectAsStateWithLifecycle("system")
    val monet by vm.graph.settings.monet.collectAsStateWithLifecycle(false)
    val scale by vm.graph.settings.pageScale.collectAsStateWithLifecycle(1f)
    val compact by vm.graph.settings.compactNavigation.collectAsStateWithLifecycle(false)
    val root by vm.graph.root.state.collectAsStateWithLifecycle()
    val preferred by vm.graph.root.preferred.collectAsStateWithLifecycle()
    if (category == "memory") { MemoryScreen(vm.graph); return }
    if (category == "models" || category == "extensions") { ExtensionsScreen(vm, if (category == "models") "模型" else "MCP"); return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (category) {
            "appearance" -> AppCard {
                Text("主题", style = MaterialTheme.typography.titleMedium)
                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
                    ListItem(headlineContent = { Text(label) }, leadingContent = { RadioButton(appearance == value, onClick = null) }, modifier = Modifier.clickable { scope.launch { vm.graph.settings.appearance(value) } }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
                }
                ListItem(headlineContent = { Text("跟随壁纸配色 · Monet") }, trailingContent = { Switch(monet, { scope.launch { vm.graph.settings.monet(it) } }) })
                Text("页面缩放 ${(scale * 100).toInt()}%")
                Slider(scale, { value -> scope.launch { vm.graph.settings.pageScale(value) } }, valueRange = .85f..1.3f)
                ListItem(headlineContent = { Text("紧凑底栏") }, trailingContent = { Switch(compact, { scope.launch { vm.graph.settings.compactNavigation(it) } }) })
            }
            "environment" -> {
                RuntimeEnvironmentCards(vm)
                val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                    if (uri != null) vm.action { withContext(Dispatchers.IO) {
                        vm.graph.assets.open("easylsp-native-host.zip").use { input -> requireNotNull(vm.graph.contentResolver.openOutputStream(uri)).use { output -> input.copyTo(output) } }
                    }; "宿主 ZIP 已导出，请在当前 root 管理器安装，随后重启手机" }
                }
                AppCard {
                    TextButton(onClick = { vm.action { "已清理 ${vm.graph.store.clearUnusedReleaseCache()} 个未安装的缓存发行；回退时需要重新下载" } }) { Text("清理未安装的发行缓存") }
                    Text("Native 宿主与存储", style = MaterialTheme.typography.titleMedium)
                    Text("宿主安装／升级后重启手机；插件版本和配置发布只需重启目标。宿主独立数据保存在 /data/adb/easylsp。")
                    TextButton(onClick = { exporter.launch("easylsp-native-host-0.9.1.zip") }) { Text("导出内置宿主安装 ZIP") }
                    TextButton(onClick = { vm.action {
                        val intent = listOf("com.sukisu.ultra", "me.weishu.kernelsu", "com.topjohnwu.magisk", "me.bmax.apatch").firstNotNullOfOrNull { vm.graph.packageManager.getLaunchIntentForPackage(it) }
                        requireNotNull(intent) { "请打开当前 root 管理器，导入刚导出的 ZIP" }
                        vm.graph.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); "已打开 root 管理器"
                    } }) { Text("打开 root 管理器") }
                    TextButton(onClick = { vm.action { withContext(Dispatchers.IO) { vm.graph.nativeHost.call("list-artifacts") }.toString() } }) { Text("查询宿主产物与回退引用") }
                    TextButton(onClick = { vm.action { withContext(Dispatchers.IO) { vm.graph.nativeHost.call("prune-history"); vm.graph.nativeHost.call("gc") }.toString() } }) { Text("移除旧宿主回退记录并清理孤立产物") }
                    Text("清理保留当前发布；已装载代码仍保留到目标进程退出。共享发行缓存可在插件中选择固定版本回退。", style = MaterialTheme.typography.bodySmall)
                }
                AppCard {
                    Text("手机操作", style = MaterialTheme.typography.titleMedium)
                    Text("开启无障碍服务后，AI 可按会话授权观察和操作手机。")
                    TextButton(onClick = { vm.graph.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("打开系统无障碍设置") }
                    var concurrency by remember { mutableStateOf(vm.graph.getSharedPreferences("harness-settings", 0).getInt("concurrency", 0).takeIf { it > 0 }?.toString().orEmpty()) }
                    Field("同时运行任务数（留空按设备资源）", concurrency) { concurrency = it }
                    TextButton(onClick = { vm.action {
                        val limit = concurrency.toIntOrNull(); require(limit == null || limit > 0)
                        vm.graph.getSharedPreferences("harness-settings", 0).edit().putInt("concurrency", limit ?: 0).commit()
                        if (limit != null) vm.graph.chat.executionService().request("host/configure", org.json.JSONObject().put("concurrency", limit))
                        "调度设置已保存"
                    } }) { Text("保存调度设置") }
                }
                AppCard {
                    Text("设备 root", style = MaterialTheme.typography.titleMedium)
                    StatusBadge(when (root.status) { "GRANTED" -> "已授权"; "CHECKING" -> "正在检测"; "DISABLED" -> "未启用"; "DENIED" -> "未获授权"; "TIMEOUT" -> "授权超时"; else -> "不可用" }, root.granted)
                    Text(root.detail, style = MaterialTheme.typography.bodySmall)
                    ListItem(headlineContent = { Text("优先使用 root") }, trailingContent = { Switch(preferred, { value -> vm.action { withContext(Dispatchers.IO) { vm.graph.root.prefer(value) }; if (value) vm.graph.root.refresh(); "执行偏好已保存" } }) })
                    Button(onClick = { vm.action { vm.graph.root.refresh().await().detail } }) { Text("检测 / 申请 root") }
                }
                val linuxStatus by vm.graph.linux.status.collectAsStateWithLifecycle()
                val linuxProgress by vm.graph.linux.progress.collectAsStateWithLifecycle()
                AppCard {
                    Text("Linux 环境", style = MaterialTheme.typography.titleMedium)
                    Text(linuxStatus)
                    if (linuxProgress > 0f && linuxProgress < 1f) LinearProgressIndicator(progress = { linuxProgress }, modifier = Modifier.fillMaxWidth())
                    Text("Ubuntu 24.04 arm64 · chroot 内用户为 root；设备 su 授权单独显示。AI 会按任务需要安装 Python、Node 等运行时。", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !vm.graph.linux.installed, onClick = { vm.action { vm.graph.linux.install(); "Ubuntu 已安装" } }) { Text("安装 Ubuntu") }
                }
                val harness = vm.graph.harness
                BackgroundPermissionsCard(vm.graph)
                ManagerKeepaliveCard(vm)
                val harnessStatus by harness.status.collectAsStateWithLifecycle()
                val serviceStatus by harness.serviceStatus.collectAsStateWithLifecycle()
                val harnessBusy by harness.busy.collectAsStateWithLifecycle()
                val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    vm.action { io.github.easylsp.app.platform.harness.HarnessExecutionService.start(vm.graph); "正在连接会话宿主" }
                }
                AppCard {
                    Text("AI 会话环境", style = MaterialTheme.typography.titleMedium)
                    Text(harnessStatus)
                    Text(serviceStatus, style = MaterialTheme.typography.bodySmall)
                    Text("首次安装需要联网。会话由 Harness 执行，前台通知和悬浮圈可在离开页面后保持任务入口。", style = MaterialTheme.typography.bodySmall)
                    if (harnessBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(enabled = vm.graph.linux.installed && !harnessBusy, onClick = { vm.action { harness.install(); "Harness 已安装" } }) { Text("安装 / 更新") }
                        TextButton(enabled = harness.installed && !harnessBusy, onClick = {
                            if (android.os.Build.VERSION.SDK_INT >= 33) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            else vm.action { io.github.easylsp.app.platform.harness.HarnessExecutionService.start(vm.graph); "正在连接会话宿主" }
                        }) { Text("启动") }
                        TextButton(enabled = !harnessBusy, onClick = { vm.action { harness.refresh(); harness.status.value } }) { Text("刷新状态") }
                        TextButton(enabled = !harnessBusy, onClick = { vm.action { harness.stop(); vm.graph.stopService(android.content.Intent(vm.graph, io.github.easylsp.app.platform.harness.HarnessExecutionService::class.java)); "宿主已停止" } }) { Text("停止") }
                    }
                }
                Text("通过设备的 su 管理器授权。终端、shell Skills、Frida 和 APK 采集共享此执行偏好。", style = MaterialTheme.typography.bodyMedium)
            }
            "storage" -> StorageScreen(vm)
            else -> {
                AppCard {
                    Text("Easylsp ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
                    Text("开源 Android 插件调试工具\nAndroid 12+ · arm64-v8a · libxposed API 101+")
                    androidx.compose.foundation.text.selection.SelectionContainer { Text(io.github.easylsp.app.platform.AppLinks.SOURCE) }
                    TextButton(onClick = { vm.action {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(io.github.easylsp.app.platform.AppLinks.SOURCE)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        try { vm.graph.startActivity(intent); "" }
                        catch (error: android.content.ActivityNotFoundException) { "没有可打开链接的浏览器，请复制上方地址" }
                    } }) { Text("GitHub 开源仓库") }
                    TextButton(onClick = { vm.action {
                        val text = withContext(Dispatchers.IO) {
                            fun read(path: String): String = requireNotNull(vm.graph.assets.list(path)).joinToString("\n\n") { name ->
                                val full = "$path/$name"
                                if (requireNotNull(vm.graph.assets.list(full)).isNotEmpty()) read(full) else "$name\n" + vm.graph.assets.open(full).bufferedReader().use { it.readText() }
                            }
                            read("third-party")
                        }
                        openOutput("第三方许可", text); "许可已打开"
                    } }) { Text("开源许可与第三方声明") }
                }
                AppCard {
                    Text("开发工具", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { vm.action {
                        val result = io.github.easylsp.app.platform.analysis.runDependencyProbe(vm.graph)
                        openOutput("依赖探针", result.getString("message", result.toString())); result.getString("message", "探针完成")
                    } }) { Text("BeanShell / DexKit / JADX 依赖探针") }
                }
            }
        }
    }
}
