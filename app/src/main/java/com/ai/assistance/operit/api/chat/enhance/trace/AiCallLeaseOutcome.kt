package com.ai.assistance.operit.api.chat.enhance.trace

import com.ai.assistance.operit.data.model.AiCallSpanEntity
import com.ai.assistance.operit.data.model.AiCallSpanStatus
import com.ai.assistance.operit.data.model.AiCallTraceEntity
import com.ai.assistance.operit.data.model.AiCallTraceStatus

/**
 * 租约终态：业务层在租约归还前回填，观测收口（`finishRoutingObservation`）据此补齐
 * span / trace 的真实结果（状态、token、成本位、错误）。
 *
 * 定位：**纯旁路观测数据**。它不参与选路、缓存键、工厂或限流的任何决策，
 * 写失败只记日志。缺省（`null`）时保持 P0 语义：SUCCESS、零 token、无错误。
 *
 * @param status 取 [AiCallSpanStatus] 之一（SUCCESS / FAILED / DEGRADED）。trace 与 span 共用同一套取值。
 * @param inputTokens 本租约生命周期内累计的输入 token（含缓存命中部分）。
 * @param outputTokens 本租约生命周期内累计的输出 token。
 * @param costMicros 预留成本位；未知为 0。
 * @param errorType 异常类型（如 `HttpStatusCodeException`），成功时为 null。
 * @param errorMessage 已脱敏的错误摘要，成功时为 null。
 * @param degraded 是否由降级链（弱模型接管）触发。降级链落地前恒为 false。
 */
data class AiCallLeaseOutcome(
    val status: String = AiCallSpanStatus.SUCCESS,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val costMicros: Long = 0L,
    val errorType: String? = null,
    val errorMessage: String? = null,
    val degraded: Boolean = false
)

/**
 * 把终态写回 trace 终态字段。纯函数，便于单测。
 *
 * 入参为 null（业务层未回填）时退化为 P0 行为：状态 SUCCESS、不标记降级、无错误。
 */
fun AiCallLeaseOutcome?.applyToTrace(trace: AiCallTraceEntity, finishedAt: Long): AiCallTraceEntity =
    trace.copy(
        finishedAt = finishedAt,
        status = this?.status ?: AiCallTraceStatus.SUCCESS,
        degraded = this?.degraded ?: false,
        errorMessage = this?.errorMessage
    )

/**
 * 把终态写回 span 终态字段。纯函数，便于单测。
 *
 * token 以 Long 承载，落库字段为 Int，超界时向上钳制避免静默溢出；负值归零。
 */
fun AiCallLeaseOutcome?.applyToSpan(
    span: AiCallSpanEntity,
    finishedAt: Long,
    durationMs: Long
): AiCallSpanEntity =
    span.copy(
        finishedAt = finishedAt,
        durationMs = durationMs,
        status = this?.status ?: AiCallSpanStatus.SUCCESS,
        inputTokens = (this?.inputTokens ?: 0L).clampToIntField(),
        outputTokens = (this?.outputTokens ?: 0L).clampToIntField(),
        costMicros = this?.costMicros ?: 0L,
        errorType = this?.errorType,
        errorMessage = this?.errorMessage
    )

private fun Long.clampToIntField(): Int =
    when {
        this <= 0L -> 0
        this >= Int.MAX_VALUE.toLong() -> Int.MAX_VALUE
        else -> toInt()
    }
