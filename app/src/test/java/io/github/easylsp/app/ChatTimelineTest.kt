package io.github.easylsp.app

import io.github.easylsp.app.data.*
import io.github.easylsp.app.feature.chat.*
import org.junit.Test
import org.junit.Assert.*

class ChatTimelineTest {
    private fun row(id: Long, role: String, content: String, blocks: String = "{}") = MessageEntity(id, "c", role, content, blocks, sampleRevision = 1, sessionId = null)
    @Test fun textToolsAndOutcomesRemainInExecutionOrder() {
        val rows = listOf(row(1, "user", "目标"), row(2, "assistant", "先查询", """{"tool_calls":[{"id":"call","function":{"name":"read","arguments":"{}"}}]}"""), row(3, "tool", "结果"), row(4, "assistant", "已经查询"), row(5, "user", "继续"))
        val turn = ChatTurnEntity("t", "c", 1, "ERROR", errorDetail = "断流", finishedAt = 10)
        assertEquals(listOf("message:1", "message:2", "tools:2", "message:4", "turn:t", "message:5"), chatTimeline(rows, listOf(turn)).map { it.key })
    }
    @Test fun retryRetainsAllOriginalAttachments() {
        val original = row(1, "user", "分析", """{"contentParts":[{"type":"text","text":"分析"},{"type":"image_url","image_url":{"url":"data:image/png;base64,abc"}},{"type":"text","text":"附件内容"}]}""")
        val attachments = messageAttachments(original)
        assertEquals(2, attachments.length()); assertEquals("附件内容", attachments.getJSONObject(1).getString("text"))
    }
    @Test fun consecutiveToolsMergeWithoutCrossingTextInteractionOrUser() {
        fun tools(id: Long, time: Long = id) = row(id, "assistant", "", """{"tool_calls":[{"id":"call-$id","function":{"name":"read_execution","arguments":"{}"}}]}""").copy(createdAt = time)
        val rows = listOf(row(1, "user", "目标").copy(createdAt = 1), tools(2), tools(3), row(4, "assistant", "已查询").copy(createdAt = 4), tools(5), tools(7), row(8, "user", "新目标").copy(createdAt = 8), tools(9))
        val interaction = ChatInteractionEntity("q", "c", "t", "APPROVAL", "{}", createdAt = 6)
        val timeline = chatTimeline(rows, emptyList(), listOf(interaction))
        assertEquals(listOf(2, 1, 1, 1), timeline.filterIsInstance<ChatTimelineItem.Tools>().map { it.calls.size })
        assertEquals("tools:2", timeline.filterIsInstance<ChatTimelineItem.Tools>().first().key)
        assertEquals("tools:2", chatTimeline(rows + tools(10), emptyList(), listOf(interaction)).filterIsInstance<ChatTimelineItem.Tools>().first().key)
    }
    @Test fun hugeRecoveryErrorHasShortPathFreeSummary() {
        val raw = "session /var/lib/easylsp-harness/uuid contains event type easylsp/limit seq 236 unknown and not marked ignorable; refusing to interpret the log"
        val summary = chatErrorSummary(raw)
        assertFalse(summary.contains("/")); assertTrue(summary.length < 40)
    }
    @Test fun paginationAndAppendRetainToolGroupKey() {
        fun tools(id: Long) = row(id, "assistant", "", """{"tool_calls":[{"id":"call-$id","function":{"name":"read","arguments":"{}"}}]}""")
        val anchors = mutableMapOf<String, Long>()
        val first = retainToolGroupKeys(chatTimeline(listOf(tools(3), tools(4)), emptyList()), anchors, null).single()
        val larger = retainToolGroupKeys(chatTimeline(listOf(tools(2), tools(3), tools(4), tools(5)), emptyList()), anchors, null).single()
        assertEquals(first.key, larger.key)
        assertEquals(first.key, retainToolGroupKeys(chatTimeline(listOf(tools(2), tools(3), tools(4)), emptyList()), mutableMapOf(), first.key).single().key)
    }
    @Test fun returnedToolDoesNotImplyCommandOrPluginValidationSuccess() {
        fun run(result: String) = ToolRunEntity("r", "c", "call", "run_command", "{}", result, "COMPLETE")
        assertTrue(toolSummary(run("""{"state":"ERROR","exitCode":2}""")).contains("失败"))
        assertTrue(toolSummary(run("""{"state":"COMPLETE","exitCode":0}""")).contains("成功"))
        assertTrue(toolSummary(run("""{"state":"RUNNING","revision":1}""")).contains("功能待验证"))
        assertTrue(toolSummary(run("""{"executionId":"queued"}""")).contains("待查询"))
    }
    @Test fun lateInteractionSplitsGroupWithoutDuplicateKeys() {
        fun tools(id: Long) = row(id, "assistant", "", """{"tool_calls":[{"id":"call-$id","function":{"name":"read","arguments":"{}"}}]}""").copy(createdAt = id)
        val anchors = mutableMapOf<String, Long>()
        val rows = listOf(tools(2), tools(4), tools(6))
        retainToolGroupKeys(chatTimeline(rows, emptyList()), anchors, null)
        val interaction = ChatInteractionEntity("q", "c", "t", "APPROVAL", "{}", createdAt = 3)
        val split = retainToolGroupKeys(chatTimeline(rows, emptyList(), listOf(interaction)), anchors, "tools:2")
        assertEquals(listOf("tools:2", "interaction:q", "tools:4"), split.map { it.key })
        // A sliding message window may no longer contain the original anchor.
        val window = retainToolGroupKeys(chatTimeline(rows.drop(1), emptyList()), anchors, null)
        assertEquals("tools:4", window.single().key)
    }
}
