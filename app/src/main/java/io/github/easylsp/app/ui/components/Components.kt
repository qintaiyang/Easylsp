package io.github.easylsp.app.ui.components

import io.github.easylsp.app.ui.icons.AppIcons
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
@Composable fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}
@Composable fun StatusBadge(label: String, positive: Boolean = false, error: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(color = when { error -> colors.errorContainer; positive -> colors.primaryContainer; else -> colors.surfaceContainerHighest }, shape = MaterialTheme.shapes.small) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = when { error -> colors.onErrorContainer; positive -> colors.onPrimaryContainer; else -> colors.onSurfaceVariant }, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}
@Composable fun EmptyState(title: String, detail: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(AppIcons.Inbox, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) Button(onClick = onAction) { Text(action) }
    }
}
@Composable fun ErrorState(message: String, retry: (() -> Unit)? = null) {
    AppCard { Text(message, color = MaterialTheme.colorScheme.error); if (retry != null) TextButton(onClick = retry) { Text("重试") } }
}
@Composable fun LoadingState(label: String = "正在加载…") {
    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp)); Text(label)
    }
}
@Composable fun AppIcon(packageName: String, modifier: Modifier = Modifier, archive: String? = null) {
    val pm = LocalContext.current.packageManager
    val bitmap by produceState<Bitmap?>(null, packageName, archive) {
        value = withContext(Dispatchers.IO) {
            val info = if (archive != null) pm.getPackageArchiveInfo(archive, 0)?.applicationInfo?.apply { sourceDir = archive; publicSourceDir = archive }
                else try { pm.getApplicationInfo(packageName, 0) } catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
            info?.loadIcon(pm)?.toBitmap(120, 120)
        }
    }
    val current = bitmap
    if (current != null) Image(current.asImageBitmap(), null, modifier.size(44.dp))
    else Icon(AppIcons.Android, null, modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
}
@Composable fun DetailHeader(title: String, subtitle: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable fun ProjectIcon(project: io.github.easylsp.app.data.ProjectEntity, vm: io.github.easylsp.app.MainViewModel) {
    val archive by produceState<String?>(null, project.id, project.sampleRevision) {
        if (project.kind == "IMPORTED") value = withContext(Dispatchers.IO) {
            vm.graph.store.dao.sample(project.id, project.sampleRevision)?.let { org.json.JSONArray(it.filesJson).getString(0) }
        }
    }
    AppIcon(project.packageName, archive = archive)
}
