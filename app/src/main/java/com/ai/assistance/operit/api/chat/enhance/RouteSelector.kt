package com.ai.assistance.operit.api.chat.enhance

import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.preferences.FunctionConfigPool
import com.ai.assistance.operit.data.preferences.FunctionRouteCandidate
import com.ai.assistance.operit.data.preferences.RouteStrategy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 一次选路的完整决策明细，供调用链与信息界面解释“为什么是它”。 */
data class RouteDecision(
    val candidate: FunctionRouteCandidate?,
    val strategy: RouteStrategy,
    val enabledCandidates: Int,
    val pickedIndex: Int,
    val reason: String
)

/**
 * 显式选路核。只决定"这一次用哪个候选"，不做失败改投、不做健康探测。
 *
 * 游标按功能类型在进程内持有，进程重启归零。
 */
object RouteSelector {

    private val cursors = ConcurrentHashMap<FunctionType, AtomicInteger>()

    /**
     * 选出一个候选，并返回完整决策明细。游标会推进。
     *
     * @return 决策明细；[RouteDecision.candidate] 为 null 表示池中没有任何启用候选，调用方必须显式失败，不得改投。
     */
    fun selectDecision(functionType: FunctionType, pool: FunctionConfigPool): RouteDecision {
        val enabled = pool.candidates.filter { it.enabled }
        if (enabled.isEmpty()) {
            return RouteDecision(
                candidate = null,
                strategy = pool.strategy,
                enabledCandidates = 0,
                pickedIndex = -1,
                reason = "没有启用的候选：显式失败，不做降级改投"
            )
        }
        return when (pool.strategy) {
            RouteStrategy.FIXED ->
                RouteDecision(
                    candidate = enabled.first(),
                    strategy = pool.strategy,
                    enabledCandidates = enabled.size,
                    pickedIndex = 0,
                    reason = "FIXED：固定取首个启用候选"
                )
            RouteStrategy.ROUND_ROBIN -> {
                val cursor = cursors.getOrPut(functionType) { AtomicInteger(0) }
                val raw = cursor.getAndIncrement()
                val index = Math.floorMod(raw, enabled.size)
                RouteDecision(
                    candidate = enabled[index],
                    strategy = pool.strategy,
                    enabledCandidates = enabled.size,
                    pickedIndex = index,
                    reason = "ROUND_ROBIN：游标序号 $raw 落在启用候选第 $index 位"
                )
            }
            RouteStrategy.WEIGHTED -> {
                val cursor = cursors.getOrPut(functionType) { AtomicInteger(0) }
                val total = enabled.sumOf { it.weight.coerceAtLeast(1) }
                val ticket = Math.floorMod(cursor.getAndIncrement(), total)
                val index = weightedPickIndex(enabled, ticket)
                RouteDecision(
                    candidate = enabled[index],
                    strategy = pool.strategy,
                    enabledCandidates = enabled.size,
                    pickedIndex = index,
                    reason = "WEIGHTED：票号 $ticket/$total 命中启用候选第 $index 位"
                )
            }
        }
    }

    /**
     * 只读查看"下一个决策"，不推进游标。仅供界面展示，不参与真正选路。
     */
    fun peekDecision(functionType: FunctionType, pool: FunctionConfigPool): RouteDecision {
        val enabled = pool.candidates.filter { it.enabled }
        if (enabled.isEmpty()) {
            return RouteDecision(
                candidate = null,
                strategy = pool.strategy,
                enabledCandidates = 0,
                pickedIndex = -1,
                reason = "没有启用的候选（只读预览）"
            )
        }
        return when (pool.strategy) {
            RouteStrategy.FIXED ->
                RouteDecision(
                    candidate = enabled.first(),
                    strategy = pool.strategy,
                    enabledCandidates = enabled.size,
                    pickedIndex = 0,
                    reason = "FIXED：固定取首个启用候选（只读预览）"
                )
            RouteStrategy.ROUND_ROBIN -> {
                val raw = cursors[functionType]?.get() ?: 0
                val index = Math.floorMod(raw, enabled.size)
                RouteDecision(
                    candidate = enabled[index],
                    strategy = pool.strategy,
                    enabledCandidates = enabled.size,
                    pickedIndex = index,
                    reason = "ROUND_ROBIN：当前游标 $raw 将命中第 $index 位（只读预览）"
                )
            }
            RouteStrategy.WEIGHTED -> {
                val total = enabled.sumOf { it.weight.coerceAtLeast(1) }
                val ticket = Math.floorMod(cursors[functionType]?.get() ?: 0, total)
                val index = weightedPickIndex(enabled, ticket)
                RouteDecision(
                    candidate = enabled[index],
                    strategy = pool.strategy,
                    enabledCandidates = enabled.size,
                    pickedIndex = index,
                    reason = "WEIGHTED：当前票号 $ticket/$total 将命中第 $index 位（只读预览）"
                )
            }
        }
    }

    /**
     * 选出一个候选。
     *
     * @return 选中的候选；若池中没有任何启用候选则返回 null（调用方必须显式失败，不得改投）。
     */
    fun select(functionType: FunctionType, pool: FunctionConfigPool): FunctionRouteCandidate? =
        selectDecision(functionType, pool).candidate

    /**
     * 只读查看"下一个候选"，不推进游标。仅供界面展示，不参与真正选路。
     */
    fun peek(functionType: FunctionType, pool: FunctionConfigPool): FunctionRouteCandidate? =
        peekDecision(functionType, pool).candidate

    /** 已按权重展开的候选列表里，命中给定票号的索引。 */
    private fun weightedPickIndex(candidates: List<FunctionRouteCandidate>, ticket: Int): Int {
        var accumulated = 0
        candidates.forEachIndexed { index, candidate ->
            accumulated += candidate.weight.coerceAtLeast(1)
            if (ticket < accumulated) return index
        }
        return candidates.lastIndex
    }
}