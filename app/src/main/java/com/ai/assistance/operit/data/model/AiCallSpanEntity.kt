package com.ai.assistance.operit.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** span 在调用链中的层级。 */
object AiCallSpanTier {
    /** 主模型池选出的落点。 */
    const val PRIMARY = "PRIMARY"
    /** 子模型池选出的落点（辅助任务）。 */
    const val SUB = "SUB"
    /** 弱模型池选出的落点（降级接管）。 */
    const val WEAK = "WEAK"
}

/** span 状态。 */
object AiCallSpanStatus {
    const val RUNNING = "RUNNING"
    const val SUCCESS = "SUCCESS"
    const val FAILED = "FAILED"
    /** 候选被跳过（未启用/被策略排除）。 */
    const val SKIPPED = "SKIPPED"
    /** 该 span 由降级链触发。 */
    const val DEGRADED = "DEGRADED"
}

/**
 * 调用链中的一个节点：一次实际发生的模型调用。
 *
 * 父子关系由 [parentSpanId] 表达，根节点为 null。树形展示由
 * `AiCallSpanTreeBuilder` 在内存中构建，数据库只存平铺结果。
 */
@Entity(
    tableName = "ai_call_span",
    indices = [
        Index(value = ["traceId"]),
        Index(value = ["traceId", "startedAt"]),
        Index(value = ["functionType"])
    ]
)
data class AiCallSpanEntity(
    @PrimaryKey val spanId: String,
    val traceId: String,
    val parentSpanId: String? = null,
    /** 取 [AiCallSpanTier] 之一。 */
    val tier: String,
    /** 取 [FunctionType] 的 name。 */
    val functionType: String,
    val configId: String? = null,
    val modelName: String? = null,
    val provider: String? = null,
    /** 本次选路策略，取 `RouteStrategy` 的 name。 */
    val strategy: String? = null,
    /** 同一父子下的第几次尝试，从 0 开始。 */
    val attemptIndex: Int = 0,
    val startedAt: Long,
    /** 未结束时为 0。 */
    val finishedAt: Long = 0L,
    val durationMs: Long = 0L,
    val status: String = AiCallSpanStatus.RUNNING,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    /** 预留成本位，未知为 0。 */
    val costMicros: Long = 0L,
    /** 异常类型，如 `HttpStatusCodeException`。 */
    val errorType: String? = null,
    /** 已脱敏。 */
    val errorMessage: String? = null
)
