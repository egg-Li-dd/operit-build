package com.ai.assistance.operit.api.chat.enhance.trace

import com.ai.assistance.operit.data.model.AiCallSpanEntity
import com.ai.assistance.operit.data.model.AiCallTraceEntity
import com.ai.assistance.operit.util.SensitiveMasking

/**
 * 入库前的脱敏视图。
 *
 * 只在“出口边界”做一次，别在采集点散着做：采集点漏一个，隐私就漏一个。
 * 目前只有自由文本字段可能夹带凭据，结构化字段（configId / modelName / provider）不含凭据。
 */
fun AiCallTraceEntity.maskedForStorage(): AiCallTraceEntity =
    copy(errorMessage = SensitiveMasking.mask(errorMessage))

fun AiCallSpanEntity.maskedForStorage(): AiCallSpanEntity =
    copy(errorMessage = SensitiveMasking.mask(errorMessage))