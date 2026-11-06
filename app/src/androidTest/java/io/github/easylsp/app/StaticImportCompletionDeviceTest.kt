package io.github.easylsp.app

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import io.github.easylsp.app.data.ProjectEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.*
import java.io.File

/** Exercises the real OpenDocument result contract, without navigating user documents. */
class StaticImportCompletionDeviceTest {
    @get:Rule(order = 0) val visible = VisibleActivityRule()
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()
    private val graph get() = ui.activity.application as EasylspApp
    @Test fun documentCancelFailureSuccessRevisionAndManualAnalysis() {
        val previous = runBlocking { graph.settings.projectFilter.first() }
        val packageName = "io.github.easylsp.demo"
        val prior = runBlocking(Dispatchers.IO) { graph.store.projectsSnapshot().firstOrNull { it.packageName == packageName && it.kind == "IMPORTED" } }
        check(prior == null) { "Keep existing imported Demo; use a fresh fixture package" }
        val directory = File(graph.cacheDir, "exports/import-fixture").apply { mkdirs() }
        val apk = File(directory, "demo.apk")
        val invalid = File(directory, "invalid.apk").apply { writeText("invalid apk fixture") }
        runBlocking(Dispatchers.IO) {
            val app = graph.packageManager.getApplicationInfo(packageName, 0)
            File(app.sourceDir).copyTo(apk, overwrite = true)
            graph.settings.projectFilter("all")
        }
        fun open(result: ActivityResult) {
            ui.waitUntil(10000) { isEnabled().matches(ui.onAllNodesWithText("导入 APK（静态分析）").onLast().fetchSemanticsNode()) }
            Intents.init()
            try {
                Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(result)
                ui.onAllNodesWithText("导入 APK（静态分析）").onLast().performClick()
                Intents.intended(hasAction(Intent.ACTION_OPEN_DOCUMENT))
            } finally { Intents.release() }
        }
        fun result(file: File) = ActivityResult(Activity.RESULT_OK, Intent().setData(FileProvider.getUriForFile(graph, graph.packageName + ".exports", file)))
        fun imported(): ProjectEntity? = runBlocking(Dispatchers.IO) { graph.store.projectsSnapshot().firstOrNull { it.packageName == packageName && it.kind == "IMPORTED" } }
        try {
            ui.onNode(hasText("项目") and isSelectable()).performClick()
            ui.waitUntil(10000) { ui.onAllNodesWithText("导入 APK（静态分析）").fetchSemanticsNodes().isNotEmpty() }
            open(ActivityResult(Activity.RESULT_CANCELED, null))
            ui.waitForIdle(); assertNull(imported())
            open(result(invalid))
            ui.waitUntil(10000) { ui.onAllNodesWithText("无法读取普通 APK；暂不支持 APKM/XAPK/APKS").fetchSemanticsNodes().isNotEmpty() }
            assertNull(imported())
            open(result(apk))
            ui.waitUntil(15000) { ui.onAllNodesWithText("静态项目").fetchSemanticsNodes().isNotEmpty() }
            val first = requireNotNull(imported()); assertEquals(1L, first.sampleRevision)
            ui.onNodeWithText("查询").assertIsEnabled().performClick()
            ui.waitUntil(30000) { ui.onAllNodesWithText("完成 · 样本 r1").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithContentDescription("返回").performClick()
            runBlocking(Dispatchers.IO) { graph.store.dao.updateProject(first.copy(archived = true)) }
            open(result(apk))
            ui.waitUntil(15000) { ui.onAllNodesWithText("1.0 · 样本 r2").fetchSemanticsNodes().isNotEmpty() }
            val second = requireNotNull(imported())
            assertEquals(first.id, second.id); assertFalse(second.archived); assertEquals(2L, second.sampleRevision)
            assertTrue(File(graph.filesDir, "samples/${first.id}/1/base.apk").isFile)
            ui.onNodeWithText("查询").assertIsEnabled().assertIsDisplayed()
        } finally {
            runBlocking(Dispatchers.IO) { imported()?.let { graph.localManagement.deleteProject(it.id) }; graph.settings.projectFilter(previous) }
            directory.deleteRecursively()
        }
    }
}
