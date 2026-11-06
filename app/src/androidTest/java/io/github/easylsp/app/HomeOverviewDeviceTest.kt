package io.github.easylsp.app

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.easylsp.app.data.ProjectEntity
import io.github.easylsp.app.platform.FrameworkStatus
import io.github.libxposed.service.IXposedScopeCallback
import io.github.libxposed.service.IXposedService
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HomeOverviewDeviceTest {
    @get:Rule(order = 0) val visible = VisibleActivityRule()
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()
    private val graph get() = ui.activity.application as EasylspApp
    private fun screenshot(name: String) {
        ui.waitForIdle()
        val window = ui.activity.window
        val bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1); var result = -1
        PixelCopy.request(window, bitmap, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
        File(graph.filesDir, "home-overview/$name.png").apply { parentFile!!.mkdirs(); outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        bitmap.recycle()
    }
    @Test fun overviewRoutesShowScopeAndOnlyEnabledPlugins() = runBlocking<Unit> {
        val appearance = graph.settings.appearance.first()
        val project = ProjectEntity(UUID.randomUUID().toString(), "io.github.easylsp.overview.fixture", "首页布局验收", "1", kind = "IMPORTED")
        val fixtures = withContext(Dispatchers.IO) {
            graph.store.dao.insertProject(project)
            listOf(
                graph.store.create(project, "首页 Java 验收", "void onLoad() {}"),
                graph.store.create(project, "首页 Native 验收", "// UI fixture", runtime = "NATIVE"),
                graph.store.create(project, "首页停用插件验收", "void onLoad() {}")
            ).also { rows -> rows.take(2).forEach { graph.store.dao.updatePlugin(it.copy(desiredEnabled = true)) } }
        }
        try {
            withTimeout(15000) { graph.framework.state.first { it.compatible } }
            withContext(Dispatchers.IO) { graph.framework.refreshScope() }
            assertTrue(graph.framework.scope.value.loaded)
            graph.settings.appearance("light")
            ui.onNodeWithTag("home-work-status", useUnmergedTree = true).assertExists()
            ui.onNodeWithText("管理器版本").assertExists()
            ui.onNodeWithText("引擎版本").assertExists()
            ui.onNodeWithText("设备型号").assertExists()
            ui.onNodeWithText("协议：", substring = true).assertDoesNotExist()
            val count = withContext(Dispatchers.IO) { graph.store.dao.observePlugins().first().count { it.desiredEnabled } }
            ui.waitUntil(10000) { ui.onAllNodes(hasTestTag("home-plugins") and hasText(count.toString())).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("home-applications").assert(hasText(graph.framework.scope.value.packages.count { it != graph.packageName }.toString()))
            screenshot("home-light")
            graph.settings.appearance("dark")
            ui.waitForIdle(); screenshot("home-dark")
            ui.onNodeWithTag("home-applications").performClick()
            ui.onNodeWithText("作用应用").assertExists()
            ui.onNodeWithTag("scope-search").performTextInput("io.github.easylsp.demo")
            ui.waitUntil(10000) { ui.onAllNodesWithTag("scope-app-io.github.easylsp.demo").fetchSemanticsNodes().isNotEmpty() }
            if ("io.github.easylsp.demo" in graph.framework.scope.value.packages) ui.onNodeWithTag("scope-app-io.github.easylsp.demo").assertIsOn()
            else ui.onNodeWithTag("scope-app-io.github.easylsp.demo").assertIsOff()
            screenshot("application-scope")
            ui.onNodeWithContentDescription("返回").performClick()
            ui.onNodeWithTag("home-plugins").performClick()
            ui.onNodeWithText("已启用插件").assertExists()
            ui.onNodeWithTag("enabled-plugin-${fixtures[0].id}").assertExists()
            ui.onNodeWithTag("enabled-plugin-${fixtures[1].id}").assertExists()
            ui.onNodeWithTag("enabled-plugin-${fixtures[2].id}").assertDoesNotExist()
            screenshot("enabled-plugins")
            ui.onNodeWithTag("enabled-plugin-${fixtures[0].id}").performClick()
            ui.onNodeWithText("保存").assertExists()
            File(graph.filesDir, "home-overview/ui-evidence.json").writeText(JSONObject().put("status", "PASSED").put("framework", graph.framework.state.value.frameworkName).put("scope", org.json.JSONArray(graph.framework.scope.value.packages.toList())).put("scopeCheckboxMatchesFramework", true).put("enabledJavaAndNativeVisible", true).put("disabledPluginExcluded", true).put("pluginEditorNavigation", true).put("lightAndDark", true).toString(2))
        } finally {
            withContext(Dispatchers.IO) {
                fixtures.forEach { graph.store.dao.updatePlugin(it.copy(desiredEnabled = false)); graph.store.delete(it.id) }
                graph.localManagement.deleteProject(project.id)
            }
            graph.settings.appearance(appearance)
        }
    }
    @Test fun realFrameworkAcceptsRequestForExistingDemoScope() = runBlocking<Unit> {
        withTimeout(15000) { graph.framework.state.first { it.compatible } }
        withContext(Dispatchers.IO) { graph.framework.refreshScope() }
        val before = graph.framework.scope.value.packages
        assertTrue("Controlled Demo must already be scoped", "io.github.easylsp.demo" in before)
        withContext(Dispatchers.IO) { graph.framework.requestScope("io.github.easylsp.demo") }
        val result = withTimeout(60000) { graph.framework.scope.first { "io.github.easylsp.demo" !in it.pending } }
        assertEquals("", result.error)
        withContext(Dispatchers.IO) { graph.framework.refreshScope() }
        assertEquals(before, graph.framework.scope.value.packages)
        File(graph.filesDir, "home-overview/scope-request-evidence.json").apply { parentFile!!.mkdirs(); writeText(JSONObject().put("status", "PASSED").put("framework", graph.framework.state.value.frameworkName).put("target", "io.github.easylsp.demo").put("existingScopePreserved", true).put("realSdkRequestCallback", true).toString(2)) }
    }
    @Test fun sdkCallbackKeepsRequestsPendingUntilApprovalAndReportsRejection() {
        val approved = mutableListOf("existing.app")
        var callback: IXposedScopeCallback? = null
        var requested = emptyList<String>()
        @Suppress("UNCHECKED_CAST")
        val remote = Proxy.newProxyInstance(IXposedService::class.java.classLoader, arrayOf(IXposedService::class.java)) { _, method, args ->
            when (method.name) {
                "getScope" -> approved.toList()
                "requestScope" -> { requested = args!![0] as List<String>; callback = args[1] as IXposedScopeCallback; null }
                "removeScope" -> { approved.removeAll((args!![0] as List<String>).toSet()); null }
                else -> error("Unexpected SDK call ${method.name}")
            }
        } as IXposedService
        val sdk = XposedService::class.java.getDeclaredConstructor(IXposedService::class.java).apply { isAccessible = true }.newInstance(remote)
        val status = FrameworkStatus()
        FrameworkStatus::class.java.getDeclaredField("service").apply { isAccessible = true }.set(status, sdk)
        status.refreshScope()
        status.requestScope("new.app")
        assertEquals(listOf("new.app"), requested)
        assertTrue("new.app" in status.scope.value.pending)
        assertFalse("new.app" in status.scope.value.packages)
        approved.add("new.app"); callback!!.onScopeRequestApproved(listOf("new.app"))
        assertTrue("new.app" in status.scope.value.packages); assertTrue(status.scope.value.pending.isEmpty())
        status.removeScope("new.app")
        assertEquals(setOf("existing.app"), status.scope.value.packages)
        status.requestScope("denied.app"); callback!!.onScopeRequestFailed("Scope request denied")
        assertFalse("denied.app" in status.scope.value.packages)
        assertTrue(status.scope.value.pending.isEmpty()); assertEquals("Scope request denied", status.scope.value.error)
    }
}
