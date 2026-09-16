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
 * 文本渲染的出口契约。
 *
 * 渲染结果直接进模型上下文，所以这里只钉两件事：**不泄凭据**、**不撑爆上下文**。
 * 时间格式化被注入成确定性实现，测试不依赖本地时区。
 */
class AiCallTraceTextFormatTest {

    private val clock: (Long) -> String = { ms -> "T$ms" }

    private fun trace(
            id: String = "trc_1",
            chatId: String? = "chat_1",
            status: String = AiCallTraceStatus.SUCCESS,
            degraded: Boolean = false,
            spanCount: Int = 1,
            errorMessage: String? = null
    ) =
            AiCallTraceEntity(
                    traceId = id,
                    chatId = chatId,
                    entryFunctionType = FunctionType.CHAT.name,
                    strategy = "FIXED",
                    startedAt = 1000L,
                    finishedAt = 1500L,
                    status = status,
                    selectedConfigId = "cfg_1",
                    selectedModelName = "gpt-x",
                    selectedProvider = "openai",
                    spanCount = spanCount,
                    degraded = degraded,
                    errorMessage = errorMessage
            )

    private fun detail(
            entity: AiCallTraceEntity = trace(),
            spans: List<AiCallSpanEntity> = emptyList(),
            totalSpanCount: Int = spans.size
    ) = AiCallTraceDetail(entity, AiCallSpanTreeBuilder.build(spans), totalSpanCount)

    private fun span(
            id: String,
            parent: String? = null,
            status: String = AiCallSpanStatus.SUCCESS,
            errorType: String? = null,
            errorMessage: String? = null
    ) =
            AiCallSpanEntity(
                    spanId = id,
                    traceId = "trc_1",
                    parentSpanId = parent,
                    tier = AiCallSpanTier.PRIMARY,
                    functionType = FunctionType.CHAT.name,
                    startedAt = 1L,
                    status = status,
                    errorType = errorType,
                    errorMessage = errorMessage
            )

    @Test
    fun formatTraceList_emptyReportsExplicitly() {
        assertEquals("No call traces recorded yet.", AiCallTraceTextFormat.formatTraceList(emptyList(), clock))
    }

    @Test
    fun formatTraceList_includesIdentityAndSelection() {
        val text = AiCallTraceTextFormat.formatTraceList(listOf(trace()), clock)

        assertTrue(text.contains("Call traces: 1"))
        assertTrue(text.contains("trc_1"))
        assertTrue(text.contains("SUCCESS"))
        assertTrue(text.contains("model=gpt-x"))
        assertTrue(text.contains("provider=openai"))
        assertTrue(text.contains("chat=chat_1"))
    }

    @Test
    fun formatTraceList_masksErrorInList() {
        val text =
                AiCallTraceTextFormat.formatTraceList(
                        listOf(trace(errorMessage = "auth failed Bearer sk-abcdef123456")),
                        clock
                )

        assertFalse(text.contains("sk-abcdef123456"))
        assertTrue(text.contains("error="))
    }

    @Test
    fun formatTraceDetail_showsSpansAndHierarchy() {
        val text =
                AiCallTraceTextFormat.formatTraceDetail(
                        detail(spans = listOf(span("root"), span("child", parent = "root"))),
                        clock
                )

        assertTrue(text.contains("Trace trc_1"))
        assertTrue(text.contains("started=T1000"))
        assertTrue(text.contains("finished=T1500"))
        assertTrue(text.contains("Spans:"))
        assertTrue(text.contains("root"))
        assertTrue(text.contains("child"))
    }

    @Test
    fun formatTraceDetail_emptyTreeSaysNone() {
        val text = AiCallTraceTextFormat.formatTraceDetail(detail(), clock)

        assertTrue(text.contains("(none)"))
    }

    @Test
    fun formatTraceDetail_notesTruncation() {
        val text =
                AiCallTraceTextFormat.formatTraceDetail(
                        detail(trace = trace(spanCount = 300), spans = listOf(span("root")), totalSpanCount = 300),
                        clock
                )

        assertTrue(text.contains("truncated"))
    }

    @Test
    fun formatTraceDetail_masksSpanError() {
        val text =
                AiCallTraceTextFormat.formatTraceDetail(
                        detail(
                                spans =
                                        listOf(
                                                span(
                                                        "root",
                                                        status = AiCallSpanStatus.FAILED,
                                                        errorType = "HttpError",
                                                        errorMessage = "api_key=leaked-secret"
                                                )
                                        )
                        ),
                        clock
                )

        assertFalse(text.contains("leaked-secret"))
        assertTrue(text.contains("HttpError"))
    }

    @Test
    fun masked_returnsNullForBlankInput() {
        assertNull(AiCallTraceTextFormat.masked(null))
        assertNull(AiCallTraceTextFormat.masked(""))
        assertNull(AiCallTraceTextFormat.masked("   "))
    }

    @Test
    fun masked_truncatesOverlongError() {
        val long = "a".repeat(500)
        val masked = AiCallTraceTextFormat.masked(long)!!

        assertEquals(201, masked.length)
        assertTrue(masked.endsWith("…"))
    }

    @Test
    fun masked_keepsShortTextIntact() {
        assertEquals("ok", AiCallTraceTextFormat.masked(" ok "))
    }
}