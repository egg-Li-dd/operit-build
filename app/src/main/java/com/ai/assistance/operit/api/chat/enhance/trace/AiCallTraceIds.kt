package com.ai.assistance.operit.api.chat.enhance.trace

import java.util.UUID

/** 调用链 id 生成。前缀便于在日志里一眼分辨，也便于按前缀清理。 */
object AiCallTraceIds {

    fun newTraceId(): String = "trc_" + UUID.randomUUID().toString().replace("-", "")

    fun newSpanId(): String = "spn_" + UUID.randomUUID().toString().replace("-", "")
}