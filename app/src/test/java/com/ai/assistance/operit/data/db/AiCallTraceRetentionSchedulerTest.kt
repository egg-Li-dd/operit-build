package com.ai.assistance.operit.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 覆盖 [AiCallTraceRetentionScheduler] 的编排契约。
 *
 * 只断言不依赖 Android / WorkManager 运行时的部分（归一化、tag、默认值），
 * 避免在纯 JVM 单测里构造 WorkRequest。
 */
class AiCallTraceRetentionSchedulerTest {

    @Test
    fun `normalizeIntervalDays 把 0 和负数归一为 1`() {
        assertEquals(1L, AiCallTraceRetentionScheduler.normalizeIntervalDays(0L))
        assertEquals(1L, AiCallTraceRetentionScheduler.normalizeIntervalDays(-5L))
    }

    @Test
    fun `normalizeIntervalDays 保留合法值`() {
        assertEquals(7L, AiCallTraceRetentionScheduler.normalizeIntervalDays(7L))
    }

    @Test
    fun `默认周期为 1 天`() {
        assertEquals(1L, AiCallTraceRetentionScheduler.DEFAULT_INTERVAL_DAYS)
    }

    @Test
    fun `WORK_TAG 契约稳定`() {
        assertEquals("ai_call_trace_retention", AiCallTraceRetentionScheduler.WORK_TAG)
    }

    @Test
    fun `默认保留天数与 worker 一致`() {
        assertEquals(7L, AiCallTraceRetentionWorker.DEFAULT_RETENTION_DAYS)
    }
}