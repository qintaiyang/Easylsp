package io.github.easylsp.app.feature.chat

import io.github.easylsp.app.data.ChatUsageEntity
import org.json.JSONObject
import java.util.Locale

/** SDK inputTokens is uncached input; cache components never overlap it. */
data class RequestUsage(val input: Long?, val output: Long?, val cacheRead: Long?, val cacheWrite: Long?, val total: Long?, val totalInput: Long?) {
    companion object {
        fun parse(raw: String): RequestUsage {
            val o = JSONObject(raw)
            fun value(vararg names: String): Long? = names.firstOrNull { o.has(it) && !o.isNull(it) }?.let { o.getLong(it) }
            val input = value("inputTokens")
            val output = value("outputTokens", "completion_tokens", "output_tokens")
            val read = value("cacheReadTokens")
            val write = value("cacheWriteTokens")
            val total = value("totalTokens", "total_tokens")
            val totalInput = if (total != null && output != null) total - output
                else value("prompt_tokens") ?: if (input != null && read != null && write != null) input + read + write else null
            return RequestUsage(input, output, read, write, total, totalInput)
        }
    }
}

data class UsageAmount(val tokens: Long?, val complete: Boolean) {
    fun label(): String = tokens?.let { "%,d".format(Locale.CHINA, it) + if (complete) "" else "（部分）" } ?: "未提供"
}
data class UsageSummary(val requests: Int, val knownRequests: Int, val input: UsageAmount, val output: UsageAmount,
    val cacheRead: UsageAmount, val cacheWrite: UsageAmount, val total: UsageAmount, val totalInput: UsageAmount,
    val cacheRate: Double?, val rateComplete: Boolean) {
    val incomplete get() = knownRequests < requests || !total.complete
    fun rateLabel() = cacheRate?.let { "%.1f%%".format(Locale.CHINA, it * 100) + if (rateComplete) "" else "（部分）" } ?: "未提供"
    fun compact(elapsedMs: Long): String {
        val count = total.tokens?.let { if (it >= 10000) "%.2f 万".format(Locale.CHINA, it / 10000.0) else it.toString() } ?: "未提供"
        val seconds = elapsedMs / 1000
        return "本轮 $count token · 缓存命中 ${rateLabel()} · $requests 次请求 · ${seconds / 60}分${seconds % 60}秒" + if (incomplete) " · 已知用量/统计不完整" else ""
    }
}
fun summarizeUsage(rows: List<ChatUsageEntity>, requestCount: Int = 0): UsageSummary {
    val unique = rows.distinctBy { it.originKey }
    val items = unique.map { RequestUsage.parse(it.usage) }
    val count = maxOf(requestCount, items.size)
    fun amount(field: (RequestUsage) -> Long?): UsageAmount {
        val values = items.mapNotNull(field)
        return UsageAmount(values.takeIf { it.isNotEmpty() }?.sum(), values.size == count && count > 0)
    }
    val rateItems = items.filter { it.totalInput != null && it.cacheRead != null }
    val denominator = rateItems.sumOf { it.totalInput!! }
    return UsageSummary(count, unique.count { it.usage != "{}" }, amount { it.input }, amount { it.output },
        amount { it.cacheRead }, amount { it.cacheWrite }, amount { it.total }, amount { it.totalInput },
        if (denominator > 0) rateItems.sumOf { it.cacheRead!! }.toDouble() / denominator else null,
        rateItems.size == count && count > 0)
}
