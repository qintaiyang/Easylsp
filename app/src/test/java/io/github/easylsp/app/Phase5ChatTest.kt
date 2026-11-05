package io.github.easylsp.app

import io.github.easylsp.app.data.*
import io.github.easylsp.app.feature.chat.*
import io.github.easylsp.app.platform.nativeplugins.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Phase5ChatTest {
    private fun usage(seq: Long, raw: String, origin: String = "s:$seq", session: String = "s") = ChatUsageEntity(session, seq, "c", origin, "$session:1", 1, raw)
    @Test fun sdkCacheComponentsAndWeightedRateMatchRecordedRound() {
        val summary = summarizeUsage(listOf(usage(1, """{"inputTokens":734569,"cacheReadTokens":3939584,"outputTokens":6826,"totalTokens":4680979}""")), 35)
        assertEquals(4674153L, summary.totalInput.tokens)
        assertEquals(734569L, summary.input.tokens)
        assertEquals(4680979L, summary.total.tokens)
        assertEquals(0.8428, summary.cacheRate!!, .0001)
        assertNull(summary.cacheWrite.tokens)
        assertTrue(summary.incomplete)
        val weighted = summarizeUsage(listOf(usage(2, """{"inputTokens":10,"cacheReadTokens":90,"outputTokens":1,"totalTokens":101}"""), usage(3, """{"inputTokens":900,"cacheReadTokens":100,"outputTokens":1,"totalTokens":1001}""")))
        assertEquals(190.0 / 1100, weighted.cacheRate!!, .00001)
    }
    @Test fun missingLegacyAndReasoningDoNotInventOrDoubleCountTokens() {
        val empty = summarizeUsage(listOf(usage(1, "{}")))
        assertNull(empty.total.tokens); assertNull(empty.cacheRate); assertTrue(empty.incomplete)
        val legacy = RequestUsage.parse("""{"prompt_tokens":12,"completion_tokens":4,"total_tokens":16}""")
        assertNull(legacy.input); assertEquals(12L, legacy.totalInput)
        val reasoning = RequestUsage.parse("""{"inputTokens":10,"outputTokens":8,"reasoningTokens":6,"totalTokens":18}""")
        assertEquals(18L, reasoning.total)
        val partial = summarizeUsage(listOf(usage(1, """{"totalTokens":10}"""), usage(2, "{}")))
        assertEquals(10L, partial.total.tokens); assertFalse(partial.total.complete)
    }
    @Test fun inheritedPrefixCountsOnceInActualHistory() {
        val old = usage(1, "{\"totalTokens\":10}")
        val inherited = old.copy(sessionId = "fork")
        val added = usage(2, "{\"totalTokens\":20}", session = "fork")
        assertEquals(30L, summarizeUsage(listOf(old, inherited, added)).total.tokens)
        assertEquals(2, summarizeUsage(listOf(old, inherited, added)).requests)
        assertEquals(30L, summarizeUsage(listOf(inherited, added)).total.tokens)
    }
    @Test fun codeFenceAndBlockIdentitySurviveStreaming() {
        val cache = MarkdownBlockCache()
        val first = cache.update("说明\n\n```te\n┌──┐\n│甲│\n└──┘\n")
        val next = cache.update("说明\n\n```te\n┌──┐\n│甲│\n└──┘\n```\n\n后续")
        assertSame(first.first(), next.first())
        assertEquals(first[1].start, next[1].start)
        assertEquals("code", first[1].kind); assertEquals("code", next[1].kind)
        assertEquals("┌──┐\n│甲│\n└──┘\n", next[1].content)
        assertEquals("code", markdownBlocks("~~~c\nint x;\n~~~").single().kind)
    }
    @Test fun tableUsesCommonMarkCellsAndLineBreaks() {
        val blocks = markdownBlocks("| 版本 | 状态 |\n| --- | --- |\n| r9 | 已加载<br>待长期验证 |\n\n下一段")
        assertEquals(listOf("table", "text"), blocks.map { it.kind })
        val rows = markdownTable(blocks.first().content)
        assertEquals("已加载\n待长期验证", rows[1][1].text)
        assertTrue(rows[0][0].header)
    }
    @Test fun successfulRoundsAlsoHaveLocalSummaryAndStableAssistantIdentity() {
        val user = MessageEntity(1, "c", "user", "需求", sampleRevision = 1, sessionId = null)
        val live = user.copy(id = -1, role = "assistant", displayKey = "assistant:s:12", status = "STREAMING")
        val saved = live.copy(id = 2, status = "COMPLETE")
        assertEquals(ChatTimelineItem.Message(live).key, ChatTimelineItem.Message(saved).key)
        assertTrue(chatTimeline(listOf(user, saved), listOf(ChatTurnEntity("t", "c", 1, "COMPLETE", finishedAt = 1))).last() is ChatTimelineItem.Outcome)
    }
    @Test fun roundSummarySurvivesPaginationWithoutItsUserMessage() {
        val message = MessageEntity(80, "c", "assistant", "末尾回复", sampleRevision = 1, sessionId = null, turnId = "s:1")
        val timeline = chatTimeline(listOf(message), listOf(ChatTurnEntity("s:1", "c", 1, "COMPLETE", finishedAt = 1)))
        assertTrue(timeline.last() is ChatTimelineItem.Outcome)
        assertEquals(1, timeline.filterIsInstance<ChatTimelineItem.Outcome>().size)
    }
    @Test fun nativeEvidenceKeepsProvenanceAndUsesSequenceForLifecycle() {
        fun event(sequence: Int, phase: String, source: String) = JSONObject("""{"uid":10001,"pid":22,"startTicks":33,"instanceId":"i","sequence":$sequence,"phase":"$phase","source":"$source"}""")
        val later = event(20, "plugin_initialized", "logcat")
        val older = event(2, "plugin_loading", "raw/logcat-window.txt").put("diagnosticRunId", "d")
        val duplicate = JSONObject(later.toString()).put("source", "raw/logcat-window.txt").put("diagnosticRunId", "d")
        val merged = mergeNativeEvidence(listOf(later), listOf(older, duplicate))
        assertEquals(2, merged.size); assertEquals("plugin_initialized", nativeLifecycle(merged)!!.getString("phase"))
        assertEquals(2, merged.first().getJSONArray("sources").length())
        assertEquals(merged.map { it.toString() }, mergeNativeEvidence(merged, listOf(older, duplicate)).map { it.toString() })
    }
    @Test fun upstreamCapacityConcurrencyAndRejectionHaveSpecificSummaries() {
        assertTrue(chatErrorSummary("HTTP503 no_available_accounts request_id=x").contains("容量不足"))
        assertTrue(chatErrorSummary("503 account_concurrency_exceeded").contains("并发已满"))
        assertTrue(chatErrorSummary("content_filter").contains("拒绝"))
    }
}
