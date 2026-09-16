package com.ai.assistance.operit.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 调用链状态。trace 与 span 共用同一套取值。 */
object AiCallTraceStatus {
    const val RUNNING = "RUNNING"
    const val SUCCESS = "SUCCESS"
    const val FAILED = "FAILED"
    /** 走了显式配置的降级链（弱模型兜底）后成功。 */
    const val DEGRADED = "DEGRADED"
}

/**
 * 一次功能级 AI 调用的调用链主记录。
 *
 * 粒度：一次请求 = 一条 trace；trace 下挂 0..n 条 [AiCallSpanEntity]。
 * [selected*] 记录“主模型实际选中的落点”，供界面回答“这次到底用了谁”。
 *
 * 写入是旁路观测，不参与选路决策。
 */
@Entity(tableName = "ai_call_trace", indices = [Index(value = ["chatId"]), Index(value = ["startedAt"])])
data class AiCallTraceEntity(
    @PrimaryKey val traceId: String,
    val chatId: String? = null,
    /** 发起调用的功能类型，取 [FunctionType] 的 name。 */
    val entryFunctionType: String,
    /** 本次是否处于路由池模式。 */
    val routePoolMode: Boolean = false,
    /** 本次选路策略，取 `RouteStrategy` 的 name。 */
    val strategy: String,
    val startedAt: Long,
    /** 未结束时为 0。 */
    val finishedAt: Long = 0L,
    val status: String = AiCallTraceStatus.RUNNING,
    val selectedConfigId: String? = null,
    val selectedModelName: String? = null,
    val selectedProvider: String? = null,
    val spanCount: Int = 0,
    /** 是否发生过降级（弱模型接管）。 */
    val degraded: Boolean = false,
    /** 已脱敏。 */
    val errorMessage: String? = null
)
