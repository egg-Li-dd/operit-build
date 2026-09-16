package com.ai.assistance.operit.api.chat.enhance.trace

import com.ai.assistance.operit.data.dao.AiCallTraceDao
import com.ai.assistance.operit.data.model.AiCallSpanEntity
import com.ai.assistance.operit.data.model.AiCallTraceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 内存版 [AiCallTraceDao] 替身。
 *
 * 只用 JUnit，不引 Room/Robolectric：真实 SQL 行为由 DAO 的 schema 测试覆盖，这里只关心
 * "查询层往 DAO 传了什么、拿回来的东西怎么渲染"。`queryTraces` 记录入参，供断言归一化结果。
 */
internal class FakeAiCallTraceDao(
        private val traces: MutableList<AiCallTraceEntity> = mutableListOf(),
        private val spans: MutableList<AiCallSpanEntity> = mutableListOf()
) : AiCallTraceDao {

    var lastQueryChatId: String? = null
        private set
    var lastQueryStatus: String? = null
        private set
    var lastQueryLimit: Int? = null
        private set

    fun seedTrace(trace: AiCallTraceEntity) {
        traces.add(trace)
    }

    fun seedSpan(span: AiCallSpanEntity) {
        spans.add(span)
    }

    override suspend fun upsertTrace(trace: AiCallTraceEntity) {
        traces.removeAll { it.traceId == trace.traceId }
        traces.add(trace)
    }

    override suspend fun upsertSpan(span: AiCallSpanEntity) {
        spans.removeAll { it.spanId == span.spanId }
        spans.add(span)
    }

    override fun observeRecentTraces(limit: Int): Flow<List<AiCallTraceEntity>> =
            flowOf(traces.sortedByDescending { it.startedAt }.take(limit))

    override suspend fun getTrace(traceId: String): AiCallTraceEntity? =
            traces.firstOrNull { it.traceId == traceId }

    override suspend fun queryTraces(
            chatId: String?,
            status: String?,
            limit: Int
    ): List<AiCallTraceEntity> {
        lastQueryChatId = chatId
        lastQueryStatus = status
        lastQueryLimit = limit
        return traces
                .asSequence()
                .filter { chatId == null || it.chatId == chatId }
                .filter { status == null || it.status == status }
                .sortedByDescending { it.startedAt }
                .take(limit)
                .toList()
    }

    override fun observeTracesForChat(chatId: String, limit: Int): Flow<List<AiCallTraceEntity>> =
            flowOf(traces.filter { it.chatId == chatId }.sortedByDescending { it.startedAt }.take(limit))

    override fun observeSpans(traceId: String): Flow<List<AiCallSpanEntity>> = flowOf(spansOf(traceId))

    override suspend fun getSpans(traceId: String): List<AiCallSpanEntity> = spansOf(traceId)

    override suspend fun deleteTracesBefore(cutoff: Long): Int {
        val doomed = traces.filter { it.startedAt < cutoff }
        traces.removeAll(doomed)
        return doomed.size
    }

    override suspend fun deleteOrphanSpans(): Int {
        val known = traces.map { it.traceId }.toSet()
        val doomed = spans.filter { it.traceId !in known }
        spans.removeAll(doomed)
        return doomed.size
    }

    override suspend fun clearTraces() {
        traces.clear()
        spans.clear()
    }

    private fun spansOf(traceId: String): List<AiCallSpanEntity> =
            spans.filter { it.traceId == traceId }
                    .sortedWith(compareBy({ it.startedAt }, { it.attemptIndex }, { it.spanId }))
}
