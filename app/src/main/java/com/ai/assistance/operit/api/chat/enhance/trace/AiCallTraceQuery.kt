package com.ai.assistance.operit.api.chat.enhance.trace

import com.ai.assistance.operit.data.dao.AiCallTraceDao
import com.ai.assistance.operit.data.model.AiCallTraceEntity
import com.ai.assistance.operit.data.model.AiCallTraceStatus

/**
 * 调用链查询的过滤条件。
 *
 * 两个字段都可为 null —— null 表示"该条件不参与筛选"。空白字符串必须在上层归一到 null，
 * 否则会退化成"查 chatId 为空串"这种永远查不到的条件。
 */
data class AiCallTraceFilter(val chatId: String? = null, val status: String? = null) {
    companion object {
        /** 与 `AiCallTraceStatus` 保持一致。未知状态直接拒掉，别把拼错的状态当"查不到"。 */
        val ALLOWED_STATUSES: Set<String> =
                setOf(
                        AiCallTraceStatus.RUNNING,
                        AiCallTraceStatus.SUCCESS,
                        AiCallTraceStatus.FAILED,
                        AiCallTraceStatus.DEGRADED
                )

        fun isAllowedStatus(value: String): Boolean = value in ALLOWED_STATUSES

        fun normalizeChatId(raw: String?): String? = raw?.trim()?.takeIf { it.isNotBlank() }

        fun normalizeStatus(raw: String?): String? = raw?.trim()?.takeIf { it.isNotBlank() }?.uppercase()
    }
}

/** 一条调用链的详情。`totalSpanCount` 是落库总数，树里可能因上限被截断。 */
data class AiCallTraceDetail(
        val trace: AiCallTraceEntity,
        val tree: AiCallSpanTree,
        val totalSpanCount: Int
) {
    val truncated: Boolean
        get() = totalSpanCount > tree.spanCount
}

/**
 * 调用链只读查询。
 *
 * 定位：**只读出口**。写入路径完全独立（`AiCallTraceRecorder` 是唯一写方），这里不做任何写操作，
 * 也不缓存结果 —— 观测数据的新鲜度比省一次查询重要。
 *
 * 三个上限都不接受调用方"我就是要全量"：
 * - trace 条数：1..[MAX_TRACE_LIMIT]
 * - 单条 trace 的 span 节点：[MAX_SPANS_PER_TRACE]，超出只截断展示、不丢总数
 */
class AiCallTraceQuery(private val dao: AiCallTraceDao) {

    companion object {
        const val DEFAULT_TRACE_LIMIT = 20
        const val MAX_TRACE_LIMIT = 200
        const val MAX_SPANS_PER_TRACE = 200

        /** 把调用方给的上限钳进合法区间；缺省用 [DEFAULT_TRACE_LIMIT]。 */
        fun normalizeLimit(requested: Int?): Int =
                (requested ?: DEFAULT_TRACE_LIMIT).coerceIn(1, MAX_TRACE_LIMIT)
    }

    /** 按过滤条件取最近的调用链，时间倒序。 */
    suspend fun recentTraces(
            filter: AiCallTraceFilter = AiCallTraceFilter(),
            limit: Int? = null
    ): List<AiCallTraceEntity> = dao.queryTraces(filter.chatId, filter.status, normalizeLimit(limit))

    /** 取单条调用链及其 span 树；traceId 不存在时返回 null（不是空详情）。 */
    suspend fun traceDetail(traceId: String): AiCallTraceDetail? {
        val normalizedId = traceId.trim()
        if (normalizedId.isEmpty()) return null

        val trace = dao.getTrace(normalizedId) ?: return null
        val spans = dao.getSpans(normalizedId)
        val bounded = if (spans.size > MAX_SPANS_PER_TRACE) spans.take(MAX_SPANS_PER_TRACE) else spans
        return AiCallTraceDetail(
                trace = trace,
                tree = AiCallSpanTreeBuilder.build(bounded),
                totalSpanCount = spans.size
        )
    }
}