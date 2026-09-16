package com.ai.assistance.operit.api.chat.enhance.trace

import com.ai.assistance.operit.data.dao.AiCallTraceDao
import com.ai.assistance.operit.data.model.AiCallSpanEntity
import com.ai.assistance.operit.data.model.AiCallTraceEntity

/** Room 落地实现。所有写入都先过一遍脱敏。 */
class RoomAiCallTraceSink(private val dao: AiCallTraceDao) : AiCallTraceSink {

    override suspend fun upsertTrace(trace: AiCallTraceEntity) {
        dao.upsertTrace(trace.maskedForStorage())
    }

    override suspend fun upsertSpan(span: AiCallSpanEntity) {
        dao.upsertSpan(span.maskedForStorage())
    }
}