package io.github.easylsp.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.easylsp.app.feature.p3.WorkspaceFilesScreen
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.File
import java.util.UUID

class WorkspaceFilesDeviceTest {
    private val ui = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(VisibleActivityRule()).around(ui)

    @Test fun browsesDirectoriesAndOpensSharedWorkspaceText() {
        val graph = ui.activity.application as EasylspApp
        val id = UUID.randomUUID().toString()
        val directory = graph.executions.directory(id)
        File(directory, "src").mkdir()
        File(directory, "src/example.txt").writeText("shared workspace content")
        var opened = ""
        val vm = MainViewModel(graph)
        try {
            ui.runOnUiThread { ui.activity.setContent { MaterialTheme { WorkspaceFilesScreen(vm, id, true) { _, text, _ -> opened = text } } } }
            ui.waitUntil(10000) { ui.onAllNodesWithText("src").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("src").performClick()
            ui.waitUntil(10000) { ui.onAllNodesWithText("example.txt").fetchSemanticsNodes().isNotEmpty() }
            ui.waitUntil(10000) { !vm.busy.value }
            ui.onNodeWithText("example.txt").performClick()
            ui.waitUntil(10000) { opened.isNotEmpty() }
            assertEquals("shared workspace content", opened)
            ui.onNodeWithText("上一级").performClick()
            ui.waitUntil(10000) { ui.onAllNodesWithText("src").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("同一项目的所有会话共享这里的文件；删除会话会保留项目文件。").assertExists()
        } finally { directory.deleteRecursively() }
    }
}
