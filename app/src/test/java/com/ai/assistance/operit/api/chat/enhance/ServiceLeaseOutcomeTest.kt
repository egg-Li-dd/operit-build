package com.ai.assistance.operit.api.chat.enhance

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.trace.AiCallLeaseOutcome
import com.ai.assistance.operit.api.chat.llmprovider.AIService
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.data.model.AiCallSpanStatus
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.ModelOption
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.util.stream.Stream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 租约收口的契约。
 *
 * [MultiServiceManager.ServiceLease] 是"业务侧累计的终态"流向"观测侧收口"的唯一通道，
 * 必须保证：回填值原样透传、不回填时传 null（P0 退化）、以及 close 幂等
 * （重复归还不能把同一条 span 收口两次）。
 */
class ServiceLeaseOutcomeTest {

    /** 只用来占位的最小 AIService 实现；本测试不触碰任何真实调用。 */
    private class StubAIService : AIService {
        override val inputTokenCount: Long = 0L
        override val cachedInputTokenCount: Long = 0L
        override val outputTokenCount: Long = 0L
        override val providerModel: String = "TEST:stub"

        override fun resetTokenCounts() = Unit

        override fun cancelStreaming() = Unit

        override suspend fun getModelsList(context: Context): Result<List<ModelOption>> =
                Result.success(emptyList())

        override suspend fun sendMessage(
                context: Context,
                chatHistory: List<PromptTurn>,
                modelParameters: List<ModelParameter<*>>,
                enableThinking: Boolean,
                stream: Boolean,
                availableTools: List<ToolPrompt>?,
                preserveThinkInHistory: Boolean,
                onTokensUpdated: suspend (Long, Long, Long) -> Unit,
                onNonFatalError: suspend (String) -> Unit,
                enableRetry: Boolean
        ): Stream<String> = throw UnsupportedOperationException("stub 不参与真实调用")

        override suspend fun testConnection(context: Context): Result<String> =
                Result.success("stub")

        override suspend fun calculateInputTokens(
                chatHistory: List<PromptTurn>,
                availableTools: List<ToolPrompt>?
        ): Long = 0L
    }

    private fun modelConfig() = ModelConfigData(id = "cfg_test", name = "测试配置")

    private fun newLease(
            captured: MutableList<AiCallLeaseOutcome?>,
            traceId: String? = null
    ) =
            MultiServiceManager.ServiceLease(
                    closeAction = { outcome -> captured.add(outcome) },
                    service = StubAIService(),
                    modelConfig = modelConfig(),
                    modelParameters = emptyList(),
                    traceId = traceId
            )

    @Test
    fun `close 把回填的终态透传给收口`() = runBlocking {
        val captured = mutableListOf<AiCallLeaseOutcome?>()
        val lease = newLease(captured)
        val outcome =
                AiCallLeaseOutcome(
                        status = AiCallSpanStatus.SUCCESS,
                        inputTokens = 999L,
                        outputTokens = 111L
                )

        lease.outcome = outcome
        lease.close()

        assertEquals(1, captured.size)
        assertEquals(outcome, captured.single())
        assertEquals(999L, captured.single()?.inputTokens)
    }

    @Test
    fun `未回填时收口收到 null`() = runBlocking {
        val captured = mutableListOf<AiCallLeaseOutcome?>()
        val lease = newLease(captured)

        lease.close()

        assertEquals(1, captured.size)
        assertNull(captured.single())
    }

    @Test
    fun `close 幂等，收口只触发一次`() = runBlocking {
        val captured = mutableListOf<AiCallLeaseOutcome?>()
        val lease = newLease(captured)

        lease.outcome = AiCallLeaseOutcome(status = AiCallSpanStatus.FAILED)
        lease.close()
        lease.close()
        lease.close()

        assertEquals(1, captured.size)
        assertEquals(AiCallSpanStatus.FAILED, captured.single()?.status)
    }

    @Test
    fun `traceId 透传给业务层`() = runBlocking {
        val captured = mutableListOf<AiCallLeaseOutcome?>()
        val lease = newLease(captured, traceId = "trc_abc")

        assertEquals("trc_abc", lease.traceId)
        assertTrue(captured.isEmpty())
    }

    @Test
    fun `未参与观测的租约 traceId 为 null`() = runBlocking {
        val captured = mutableListOf<AiCallLeaseOutcome?>()

        assertNull(newLease(captured).traceId)
    }
}