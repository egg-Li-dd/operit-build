package com.ai.assistance.operit.api.chat.enhance.trace

import com.ai.assistance.operit.data.model.AiCallSpanEntity
import com.ai.assistance.operit.data.model.AiCallSpanStatus
import com.ai.assistance.operit.data.model.AiCallSpanTier
import com.ai.assistance.operit.data.model.AiCallTraceEntity
import com.ai.assistance.operit.data.model.AiCallTraceStatus
import com.ai.assistance.operit.data.model.FunctionType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只读查询层的边界契约。
 *
 * 三条必须钉死的性质：
 * 1. 上限永远钳得住 —— 调用方传 0 / 负数 / 天文数字，都不能把库拉爆；
 * 2. traceId 为空或查无此链时返回 null（不是空壳详情），上层才能给出正确的"不存在"语义；
 * 3. span 超过单 trace 上限时只截断展示，`totalSpanCount` 必须保留真实总数，绝不静默丢数。
 */
class AiCallTraceQueryTest {

    private fun trace(
            id: String,
            chatId: String? = "chat_1",
            status: String = AiCallTraceStatus.SUCCESS,
            startedAt: Long = 0L
    ) =
            AiCallTraceEntity(
                    traceId = id,
                    chatId = chatId,
                    entryFunctionType = FunctionType.CHAT.name,
                    strategy = "FIXED",
                    startedAt = startedAt,
                    finishedAt = startedAt + 10,
                    status = status
            )

    private fun span(id: String, traceId: String, parent: String? = null, startedAt: Long = 0L) =
            AiCallSpanEntity(
                    spanId = id,
                    traceId = traceId,
                    parentSpanId = parent,
                    tier = AiCallSpanTier.PRIMARY,
                    functionType = FunctionType.CHAT.name,
                    startedAt = startedAt,
                    status = AiCallSpanStatus.SUCCESS
            )

    @Test
    fun normalizeLimit_clampsIntoAllowedRange() {
        assertEquals(AiCallTraceQuery.DEFAULT_TRACE_LIMIT, AiCallTraceQuery.normalizeLimit(null))
        assertEquals(1, AiCallTraceQuery.normalizeLimit(0))
        assertEquals(1, AiCallTraceQuery.normalizeLimit(-5))
        assertEquals(7, AiCallTraceQuery.normalizeLimit(7))
        assertEquals(AiCallTraceQuery.MAX_TRACE_LIMIT, AiCallTraceQuery.normalizeLimit(999_999))
    }

    @Test
    fun recentTraces_pushesNormalizedLimitAndFilterDownToDao() = runTest {
        val dao = FakeAiCallTraceDao()
        dao.seedTrace(trace("t1", startedAt = 1))
        val query = AiCallTraceQuery(dao)

        query.recentTraces(AiCallTraceFilter(chatId = "chat_1", status = "SUCCESS"), limit = 999_999)

        assertEquals(AiCallTraceQuery.MAX_TRACE_LIMIT, dao.lastQueryLimit)
        assertEquals("chat_1", dao.lastQueryChatId)
        assertEquals("SUCCESS", dao.lastQueryStatus)
    }

    @Test
    fun recentTraces_defaultsToDefaultLimitWhenUnspecified() = runTest {
        val dao = FakeAiCallTraceDao()
        val query = AiCallTraceQuery(dao)

        query.recentTraces()

        assertEquals(AiCallTraceQuery.DEFAULT_TRACE_LIMIT, dao.lastQueryLimit)
    }

    @Test
    fun recentTraces_filtersByChatAndStatus() = runTest {
        val dao = FakeAiCallTraceDao()
        dao.seedTrace(trace("a", chatId = "chat_1", status = AiCallTraceStatus.SUCCESS, startedAt = 1))
        dao.seedTrace(trace("b", chatId = "chat_2", status = AiCallTraceStatus.SUCCESS, startedAt = 2))
        dao.seedTrace(trace("c", chatId = "chat_1", status = AiCallTraceStatus.FAILED, startedAt = 3))
        val query = AiCallTraceQuery(dao)

        val result = query.recentTraces(AiCallTraceFilter(chatId = "chat_1", status = AiCallTraceStatus.SUCCESS))

        assertEquals(listOf("a"), result.map { it.traceId })
    }

    @Test
    fun traceDetail_blankIdReturnsNullWithoutTouchingDao() = runTest {
        val dao = FakeAiCallTraceDao()
        dao.seedTrace(trace("t1"))
        val query = AiCallTraceQuery(dao)

        assertNull(query.traceDetail(""))
        assertNull(query.traceDetail("   "))
    }

    @Test
    fun traceDetail_missingTraceReturnsNull() = runTest {
        val dao = FakeAiCallTraceDao()
        val query = AiCallTraceQuery(dao)

        assertNull(query.traceDetail("nope"))
    }

    @Test
    fun traceDetail_buildsTreeAndCountsSpans() = runTest {
        val dao = FakeAiCallTraceDao()
        dao.seedTrace(trace("t1"))
        dao.seedSpan(span("root", "t1", startedAt = 1))
        dao.seedSpan(span("child", "t1", parent = "root", startedAt = 2))
        val query = AiCallTraceQuery(dao)

        val detail = query.traceDetail("t1")!!

        assertEquals("t1", detail.trace.traceId)
        assertEquals(2, detail.tree.spanCount)
        assertEquals(2, detail.totalSpanCount)
        assertFalse(detail.truncated)
    }

    @Test
    fun traceDetail_truncatesSpansButKeepsRealTotal() = runTest {
        val dao = FakeAiCallTraceDao()
        dao.seedTrace(trace("t1"))
        val overflow = AiCallTraceQuery.MAX_SPANS_PER_TRACE + 5
        repeat(overflow) { index -> dao.seedSpan(span("s$index", "t1", startedAt = index.toLong())) }
        val query = AiCallTraceQuery(dao)

        val detail = query.traceDetail("t1")!!

        assertEquals(AiCallTraceQuery.MAX_SPANS_PER_TRACE, detail.tree.spanCount)
        assertEquals(overflow, detail.totalSpanCount)
        assertTrue(detail.truncated)
    }
}