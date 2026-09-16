package com.ai.assistance.operit.api.chat.enhance.trace

import com.ai.assistance.operit.data.model.AiCallSpanEntity
import com.ai.assistance.operit.data.model.AiCallTraceEntity

/**
 * 调用链落库出口。
 *
 * 抽象出来有两个目的：
 * 1. 真实宿主写 Room；单元测试用内存实现，不依赖 Android。
 * 2. 未来若要换宿主（导出、上报），只替换实现，不动调用点。
 */
interface AiCallTraceSink {
    suspend fun upsertTrace(trace: AiCallTraceEntity)

    suspend fun upsertSpan(span: AiCallSpanEntity)
}