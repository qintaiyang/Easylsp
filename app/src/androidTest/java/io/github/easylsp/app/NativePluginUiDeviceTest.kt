package io.github.easylsp.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import io.github.easylsp.app.feature.plugins.NativePluginActions
import io.github.easylsp.app.ui.theme.EasylspTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.File

class NativePluginUiDeviceTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(VisibleActivityRule()).around(compose)

    @Test fun buildSelectionPolicyPublicationAndDisableUseProductionActions() {
        val graph = compose.activity.application as EasylspApp
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val project = runBlocking(Dispatchers.IO) { requireNotNull(graph.store.dao.projectByPackage("io.github.easylsp.demo")) }
        val plugin = runBlocking(Dispatchers.IO) {
            withTimeout(10000) { while (!graph.framework.state.value.active) delay(50) }
            val source = graph.assets.open("early-symbol/src/main.c").bufferedReader().use { it.readText() }
            graph.store.create(project, "Native UI acceptance", source, runtime = "NATIVE")
        }
        try {
            compose.activity.runOnUiThread { compose.activity.setContent {
                EasylspTheme("light") { Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.systemBarsPadding()) { NativePluginActions(vm, project, plugin) }
                } }
            } }
            compose.waitUntil(10000) { !vm.busy.value }
            compose.onNodeWithText("构建").performClick()
            compose.waitUntil(30000) { vm.nativeBuilds.value.any { it.pluginId == plugin.id && it.state == "SUCCEEDED" } }
            compose.onNodeWithText("构建与发布").performClick()
            compose.onNodeWithText("启用，下次启动生效").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("选择发布").performScrollTo().performClick()
            compose.onNodeWithText("启用，下次启动生效").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("仅 Native：跳过 Java 插件和 Inspector").performScrollTo().performClick()
            compose.onNodeWithText("启用，下次启动生效").performScrollTo().assertIsEnabled()
            val file = File(graph.filesDir, "native-ui-publication.png")
            compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap().let { bitmap ->
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.onNodeWithText("启用，下次启动生效").performClick()
            compose.waitUntil(10000) { vm.nativeDeployments.value.any { it.projectId == project.id && it.policy == "NATIVE_ONLY" && it.state == "PUBLISHED" && it.snapshot.contains(plugin.id) } }
            compose.waitUntil(10000) { !vm.busy.value }
            compose.onNodeWithText("查询实际执行结果").performScrollTo().performClick()
            compose.waitUntil(10000) { vm.nativeReports.value.containsKey(plugin.id) }
            assertTrue(vm.nativeReports.value.getValue(plugin.id).contains("尚无执行证据"))
            compose.onNodeWithText("关闭").performClick()
            compose.onNodeWithText("停用").performClick()
            compose.waitUntil(10000) { !vm.busy.value }
            runBlocking(Dispatchers.IO) { assertFalse(requireNotNull(graph.store.dao.plugin(plugin.id)).desiredEnabled) }
        } finally {
            compose.activity.runOnUiThread { compose.activity.setContent {} }
            compose.waitForIdle()
            runBlocking(Dispatchers.IO) { graph.nativeDeployments.disable(plugin); graph.store.delete(plugin.id) }
        }
    }
}
