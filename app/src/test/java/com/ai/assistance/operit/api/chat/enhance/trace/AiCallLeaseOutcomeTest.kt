package com.ai.assistance.operit.api.chat.enhance.trace

import com.ai.assistance.operit.data.model.AiCallSpanEntity
import com.ai.assistance.operit.data.model.AiCallSpanStatus
import com.ai.assistance.operit.data.model.AiCallSpanTier
import com.ai.assistance.operit.data.model.AiCallTraceEntity
import com.ai.assistance.operit.data.model.AiCallTraceStatus
import com.ai.assistance.operit.data.model.FunctionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 租约终态回填的纯函数契约。
 *
 * 这块是全链路唯一"业务 → 观测"的数据交接面，必须保证两件事：
 * 1. 业务层不回填（null）时行为与 P0 完全一致，不能把观测写坏；
 * 2. 回填值越界（负数 / 超出 Int 的 token）时不能静默溢出。
 */
class AiCallLeaseOutcomeTest {

    private fun trace() =
            AiCallTraceEntity(
                    traceId = "trc_test",
                    chatId = "chat_1",
                    entryFunctionType = FunctionType.CHAT.name,
                    strategy = "FIXED",
                    startedAt = 1000L,
                    status = AiCallTraceStatus.RUNNING
            )

    private fun span() =
            AiCallSpanEntity(
                    spanId = "spn_test",
                    traceId = "trc_test",
                    tier = AiCallSpanTier.PRIMARY,
                    functionType = FunctionType.CHAT.name,
                    startedAt = 1000L,
                    status = AiCallSpanStatus.RUNNING
            )

    @Test
    fun `未回填时退化为 P0 语义`() {
        val outcome: AiCallLeaseOutcome? = null

        val updatedTrace = outcome.applyToTrace(trace(), finishedAt = 2000L)
        val updatedSpan = outcome.applyToSpan(span(), finishedAt = 2000L, durationMs = 1000L)

        assertEquals(AiCallTraceStatus.SUCCESS, updatedTrace.status)
        assertEquals(AiCallSpanStatus.SUCCESS, updatedSpan.status)
        assertEquals(0, updatedSpan.inputTokens)
        assertEquals(0, updatedSpan.outputTokens)
        assertEquals(0L, updatedSpan.costMicros)
        assertNull(updatedSpan.errorType)
        assertNull(updatedSpan.errorMessage)
        assertFalse(updatedTrace.degraded)
    }

    @Test
    fun `回填终态写入状态与 token`() {
        val outcome =
                AiCallLeaseOutcome(
                        status = AiCallSpanStatus.SUCCESS,
                        inputTokens = 1200L,
                        outputTokens = 340L,
                        costMicros = 7L
                )

        val updatedTrace = outcome.applyToTrace(trace(), finishedAt = 2000L)
        val updatedSpan = outcome.applyToSpan(span(), finishedAt = 2000L, durationMs = 1000L)

        assertEquals(2000L, updatedTrace.finishedAt)
        assertEquals(2000L, updatedSpan.finishedAt)
        assertEquals(1000L, updatedSpan.durationMs)
        assertEquals(1200, updatedSpan.inputTokens)
        assertEquals(340, updatedSpan.outputTokens)
        assertEquals(7L, updatedSpan.costMicros)
    }

    @Test
    fun `失败终态带上错误类型与消息`() {
        val outcome =
                AiCallLeaseOutcome(
                        status = AiCallSpanStatus.FAILED,
                        errorType = "HttpStatusCodeException",
                        errorMessage = "503 Service Unavailable"
                )

        val updatedSpan = outcome.applyToSpan(span(), finishedAt = 2000L, durationMs = 1000L)
        val updatedTrace = outcome.applyToTrace(trace(), finishedAt = 2000L)

        assertEquals(AiCallSpanStatus.FAILED, updatedSpan.status)
        assertEquals(AiCallTraceStatus.FAILED, updatedTrace.status)
        assertEquals("HttpStatusCodeException", updatedSpan.errorType)
        assertEquals("503 Service Unavailable", updatedSpan.errorMessage)
        assertEquals("503 Service Unavailable", updatedTrace.errorMessage)
    }

    @Test
    fun `负 token 归零，不写坏统计`() {
        val outcome = AiCallLeaseOutcome(inputTokens = -5L, outputTokens = -1L)

        val updatedSpan = outcome.applyToSpan(span(), finishedAt = 2000L, durationMs = 1000L)

        assertEquals(0, updatedSpan.inputTokens)
        assertEquals(0, updatedSpan.outputTokens)
    }

    @Test
    fun `超出 Int 的 token 向上钳制而非溢出`() {
        val overflow = Int.MAX_VALUE.toLong() + 1024L
        val outcome = AiCallLeaseOutcome(inputTokens = overflow, outputTokens = overflow)

        val updatedSpan = outcome.applyToSpan(span(), finishedAt = 2000L, durationMs = 1000L)

        assertEquals(Int.MAX_VALUE, updatedSpan.inputTokens)
        assertEquals(Int.MAX_VALUE, updatedSpan.outputTokens)
    }

    @Test
    fun `degraded 只由显式标记驱动`() {
        val plain = AiCallLeaseOutcome(status = AiCallSpanStatus.SUCCESS)
        val degraded =
                AiCallLeaseOutcome(status = AiCallTraceStatus.DEGRADED, degraded = true)

        assertFalse(plain.applyToTrace(trace(), finishedAt = 2000L).degraded)
        assertTrue(degraded.applyToTrace(trace(), finishedAt = 2000L).degraded)
        // 降级状态取值在 trace / span 之间共用，不能分叉
        assertEquals(
                AiCallTraceStatus.DEGRADED,
                degraded.applyToSpan(span(), finishedAt = 2000L, durationMs = 1000L).status
        )
    }

    @Test
    fun `回填是纯函数，不改动入参快照`() {
        val original = span()
        val outcome = AiCallLeaseOutcome(inputTokens = 10L, outputTokens = 20L)

        outcome.applyToSpan(original, finishedAt = 2000L, durationMs = 1000L)

        assertEquals(AiCallSpanStatus.RUNNING, original.status)
        assertEquals(0, original.inputTokens)
        assertEquals(0L, original.finishedAt)
    }
}
