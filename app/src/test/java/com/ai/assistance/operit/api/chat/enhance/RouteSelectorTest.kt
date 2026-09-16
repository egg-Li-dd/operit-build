package com.ai.assistance.operit.api.chat.enhance

import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.preferences.FunctionConfigPool
import com.ai.assistance.operit.data.preferences.FunctionRouteCandidate
import com.ai.assistance.operit.data.preferences.RouteStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 显式选路核的行为契约：三种策略的落点、空池显式失败、peek 不推进游标。
 *
 * 游标按 FunctionType 在进程内持有，故每个用例各用一个独立的 FunctionType，避免相互污染。
 */
class RouteSelectorTest {

    private fun pool(strategy: RouteStrategy, vararg candidates: FunctionRouteCandidate) =
            FunctionConfigPool(candidates = candidates.toList(), strategy = strategy)

    private fun candidate(id: String, weight: Int = 1, enabled: Boolean = true) =
            FunctionRouteCandidate(configId = id, modelIndex = 0, enabled = enabled, weight = weight)

    @Test
    fun fixed_picksFirstEnabledAndDoesNotAdvance() {
        val p =
                pool(
                        RouteStrategy.FIXED,
                        candidate("disabled", enabled = false),
                        candidate("first-enabled"),
                        candidate("second-enabled")
                )
        assertEquals("first-enabled", RouteSelector.select(FunctionType.CHAT, p)?.configId)
        assertEquals("first-enabled", RouteSelector.select(FunctionType.CHAT, p)?.configId)
    }

    @Test
    fun roundRobin_cyclesThroughEnabledCandidatesInOrder() {
        val p = pool(RouteStrategy.ROUND_ROBIN, candidate("a"), candidate("b"), candidate("c"))
        val picks = (1..4).map { RouteSelector.select(FunctionType.SUMMARY, p)?.configId }
        assertEquals(listOf("a", "b", "c", "a"), picks)
    }

    @Test
    fun weighted_dispensesTicketsInProportionToWeight() {
        // 权重 3:1 -> 每 4 张票中前 3 张给 a，最后 1 张给 b
        val p =
                pool(
                        RouteStrategy.WEIGHTED,
                        candidate("a", weight = 3),
                        candidate("b", weight = 1)
                )
        val picks = (1..4).map { RouteSelector.select(FunctionType.MEMORY, p)?.configId }
        assertEquals(listOf("a", "a", "a", "b"), picks)
    }

    @Test
    fun weighted_clampsNonPositiveWeightToOne() {
        val p =
                pool(
                        RouteStrategy.WEIGHTED,
                        candidate("a", weight = 0),
                        candidate("b", weight = -5)
                )
        val picks = (1..2).map { RouteSelector.select(FunctionType.TRANSLATION, p)?.configId }
        assertEquals(listOf("a", "b"), picks)
    }

    @Test
    fun emptyPool_returnsNullForEveryStrategy() {
        assertNull(RouteSelector.select(FunctionType.GREP, pool(RouteStrategy.FIXED)))
        assertNull(RouteSelector.select(FunctionType.GREP, pool(RouteStrategy.ROUND_ROBIN)))
        assertNull(RouteSelector.select(FunctionType.GREP, pool(RouteStrategy.WEIGHTED)))
    }

    @Test
    fun allDisabled_returnsNullWithoutSilentReroute() {
        val p =
                pool(
                        RouteStrategy.FIXED,
                        candidate("a", enabled = false),
                        candidate("b", enabled = false)
                )
        assertNull(RouteSelector.select(FunctionType.UI_CONTROLLER, p))
    }

    @Test
    fun peek_readsNextWithoutAdvancingCursor() {
        val p = pool(RouteStrategy.ROUND_ROBIN, candidate("a"), candidate("b"))
        assertEquals("a", RouteSelector.peek(FunctionType.TITLE_GENERATION, p)?.configId)
        assertEquals("a", RouteSelector.peek(FunctionType.TITLE_GENERATION, p)?.configId)
        assertEquals("a", RouteSelector.select(FunctionType.TITLE_GENERATION, p)?.configId)
        assertEquals("b", RouteSelector.peek(FunctionType.TITLE_GENERATION, p)?.configId)
    }
}
