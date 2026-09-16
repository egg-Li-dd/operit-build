package com.ai.assistance.operit.data.db

import com.ai.assistance.operit.data.dao.AiCallTraceDao
import com.ai.assistance.operit.data.model.AiCallSpanEntity
import com.ai.assistance.operit.data.model.AiCallTraceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 覆盖 [AiCallTraceRetention] 的清理语义。
 *
 * 刻意不碰 WorkManager / Room 运行时：清理逻辑已抽成纯函数，用内存 FakeDao 断言即可。
 */
class AiCallTraceRetentionWorkerTest {

    private class FakeDao : AiCallTraceDao {
        val events = mutableListOf<String>()
        val observedCutoffs = mutableListOf<Long>()
        var tracesToRemove = 0
        var spansToRemove = 0

        override suspend fun upsertTrace(trace: AiCallTraceEntity) = Unit

        override suspend fun upsertSpan(span: AiCallSpanEntity) = Unit

        override fun observeRecentTraces(limit: Int): Flow<List<AiCallTraceEntity>> = flowOf(emptyList())

        override suspend fun getTrace(traceId: String): AiCallTraceEntity? = null

        override fun observeTracesForChat(chatId: String, limit: Int): Flow<List<AiCallTraceEntity>> =
            flowOf(emptyList())

        override fun observeSpans(traceId: String): Flow<List<AiCallSpanEntity>> = flowOf(emptyList())

        override suspend fun getSpans(traceId: String): List<AiCallSpanEntity> = emptyList()

        override suspend fun deleteTracesBefore(cutoff: Long): Int {
            events += "traces"
            observedCutoffs += cutoff
            return tracesToRemove
        }

        override suspend fun deleteOrphanSpans(): Int {
            events += "spans"
            return spansToRemove
        }

        override suspend fun clearTraces() = Unit
    }

    @Test
    fun `purge 回传删除计数`() = runBlocking {
        val dao = FakeDao().apply {
            tracesToRemove = 3
            spansToRemove = 5
        }

        val result = AiCallTraceRetention.purge(dao, cutoff = 1_000L)

        assertEquals(3, result.traces)
        assertEquals(5, result.spans)
    }

    @Test
    fun `purge 先删 trace 再清孤儿 span`() = runBlocking {
        val dao = FakeDao()

        AiCallTraceRetention.purge(dao, cutoff = 1_000L)

        assertEquals(listOf("traces", "spans"), dao.events)
    }

    @Test
    fun `purge 把 cutoff 原样透传给 DAO`() = runBlocking {
        val dao = FakeDao()

        AiCallTraceRetention.purge(dao, cutoff = 123_456L)

        assertEquals(listOf(123_456L), dao.observedCutoffs)
    }

    @Test
    fun `cutoffFor 按天数回推`() {
        val now = 1_000_000_000_000L

        val cutoff = AiCallTraceRetention.cutoffFor(now, retentionDays = 7L)

        assertEquals(now - TimeUnit.DAYS.toMillis(7), cutoff)
    }

    @Test
    fun `cutoffFor 负天数按 0 处理`() {
        val now = 1_000_000_000_000L

        assertEquals(now, AiCallTraceRetention.cutoffFor(now, retentionDays = -3L))
    }

    @Test
    fun `默认保留 7 天`() {
        assertEquals(7L, AiCallTraceRetentionWorker.DEFAULT_RETENTION_DAYS)
    }
}