package io.github.easylsp.app.feature.chat

import io.github.easylsp.app.data.*
import org.json.JSONObject

/** A display projection only: message order and tool call IDs come from execution records. */
sealed interface ChatTimelineItem {
    val key: String
    data class Message(val row: MessageEntity) : ChatTimelineItem { override val key = row.displayKey.ifEmpty { "message:${row.id}" } }
    data class Tools(val messageId: Long, val calls: List<org.json.JSONObject>, val reasoning: List<String> = emptyList(), val messageIds: List<Long> = listOf(messageId)) : ChatTimelineItem { override val key = "tools:$messageId" }
    data class Outcome(val turn: ChatTurnEntity) : ChatTimelineItem { override val key = "turn:${turn.id}" }
    data class Interaction(val row: ChatInteractionEntity) : ChatTimelineItem { override val key = "interaction:${row.id}" }
}

fun chatTimeline(messages: List<MessageEntity>, turns: List<ChatTurnEntity>, interactions: List<ChatInteractionEntity> = emptyList()): List<ChatTimelineItem> = buildList {
    val outcomes = turns.filter { it.finishedAt != null }.groupBy { it.userMessageId }
    var userId = 0L
    var currentTurnId = ""
    val renderedOutcomes = mutableSetOf<String>()
    fun outcome() {
        val matching = if (currentTurnId.isNotEmpty()) turns.filter { it.id == currentTurnId && it.finishedAt != null } else outcomes[userId].orEmpty()
        matching.filter { renderedOutcomes.add(it.id) }.forEach { add(ChatTimelineItem.Outcome(it)) }
    }
    val cards = interactions.sortedBy { it.createdAt }.iterator()
    var card = if (cards.hasNext()) cards.next() else null
    fun cardsBefore(time: Long) {
        while (card != null && card!!.createdAt <= time) {
            add(ChatTimelineItem.Interaction(card!!))
            card = if (cards.hasNext()) cards.next() else null
        }
    }
    var previousTime = 0L
    messages.forEach { row ->
        val turnBoundary = turns.any { it.finishedAt != null && it.finishedAt in previousTime until row.createdAt }
        previousTime = row.createdAt
        cardsBefore(row.createdAt)
        if (row.role == "user") { outcome(); userId = row.id; currentTurnId = row.turnId }
        else if (row.turnId.isNotEmpty() && row.turnId != currentTurnId) { outcome(); currentTurnId = row.turnId }
        if (row.role != "tool") {
            val blocks = JSONObject(row.blocks)
            if (row.role == "user" || row.content.isNotBlank() || (blocks.optJSONArray("tool_calls") == null && blocks.optString("reasoning").isNotEmpty()) || row.status == "STREAMING") add(ChatTimelineItem.Message(row))
            blocks.optJSONArray("tool_calls")?.let { calls ->
                val tools = (0 until calls.length()).map { calls.getJSONObject(it) }
                val reasoning = listOf(blocks.optString("reasoning")).filter(String::isNotEmpty)
                val previous = lastOrNull()
                if (previous is ChatTimelineItem.Tools && !turnBoundary) {
                    removeAt(lastIndex); add(previous.copy(calls = previous.calls + tools, reasoning = previous.reasoning + reasoning, messageIds = previous.messageIds + row.id))
                } else add(ChatTimelineItem.Tools(row.id, tools, reasoning))
            }
        }
    }
    cardsBefore(Long.MAX_VALUE)
    outcome()
}

fun messageAttachments(message: MessageEntity): org.json.JSONArray {
    val blocks = JSONObject(message.blocks)
    blocks.optJSONArray("attachments")?.let { return it }
    val parts = blocks.optJSONArray("contentParts") ?: return org.json.JSONArray()
    return org.json.JSONArray().apply { for (i in 1 until parts.length()) put(parts.get(i)) }
}

/** Retain an existing group's identity when pagination reveals more calls at its start. */
internal fun retainToolGroupKeys(items: List<ChatTimelineItem>, anchors: MutableMap<String, Long>, readingKey: String?): List<ChatTimelineItem> {
    val owners = items.filterIsInstance<ChatTimelineItem.Tools>().flatMap { group -> group.messageIds.map { it to group.messageId } }.toMap()
    val claimed = mutableSetOf<Long>()
    return items.map { item ->
        if (item !is ChatTimelineItem.Tools) item else {
            val readingId = readingKey?.removePrefix("tools:")?.toLongOrNull()?.takeIf { it in item.messageIds }
            // Late interaction/turn records can split a previously merged group.
            val anchor = readingId ?: item.calls.firstNotNullOfOrNull { call ->
                anchors[call.getString("id")]?.takeIf { it !in claimed && (owners[it] == null || owners[it] == item.messageId) }
            } ?: item.messageId
            claimed += anchor
            item.calls.forEach { anchors[it.getString("id")] = anchor }
            item.copy(messageId = anchor)
        }
    }
}
