package com.ai.assistance.operit.api.chat.enhance

import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.preferences.FunctionConfigPool
import com.ai.assistance.operit.data.preferences.FunctionRouteCandidate
import com.ai.assistance.operit.data.preferences.RouteStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 决策明细契约：selectDecision/peekDecision 必须回答"这次为什么选了它"。
 *
 * 游标按 FunctionType 在进程内持有、且与 RouteSelectorTest 共用，JUnit 方法执行顺序不保证。
 * 故这里只做相对断言（循环/比例），不假设游标起点。
 */
class RouteSelectorDecisionTest {

    private fun pool(strategy: RouteStrategy, vararg candidates: FunctionRouteCandidate) =
            FunctionConfigPool(candidates = candidates.toList(), strategy = strategy)

    private fun candidate(id: String, weight: Int = 1, enabled: Boolean = true) =
            FunctionRouteCandidate(configId = id, modelIndex = 0, enabled = enabled, weight = weight)

    @Test
    fun fixed_reportsStrategyAndEnabledCount() {
        val p =
                pool(
                        RouteStrategy.FIXED,
                        candidate("disabled", enabled = false),
                        candidate("first-enabled"),
                        candidate("second-enabled")
                )
        val decision = RouteSelector.selectDecision(FunctionType.IMAGE_RECOGNITION, p)
        assertEquals(RouteStrategy.FIXED, decision.strategy)
        assertEquals("first-enabled", decision.candidate?.configId)
        assertEquals(2, decision.enabledCandidates)
        assertEquals(0, decision.pickedIndex)
        assertTrue(decision.reason.contains("FIXED"))
    }

    @Test
    fun roundRobin_cyclesEnabledCandidatesAndReportsReason() {
        val p = pool(RouteStrategy.ROUND_ROBIN, candidate("a"), candidate("b"), candidate("c"))
        val decisions =
                (1..4).map { RouteSelector.selectDecision(FunctionType.ROLE_RESPONSE_PLANNER, p) }
        val picks = decisions.map { it.candidate?.configId }
        // 3 循环：第 4 次回到起点，前 3 次互不相同（不依赖游标起点）
        assertEquals(picks[0], picks[3])
        assertEquals(3, picks.take(3).toSet().size)
        assertEquals(decisions[0].pickedIndex, decisions[3].pickedIndex)
        assertEquals(3, decisions.map { it.pickedIndex }.take(3).toSet().size)
        assertTrue(decisions.first().reason.contains("ROUND_ROBIN"))
    }

    @Test
    fun weighted_keepsThreeToOneRatioAcrossFourTickets() {
        val p =
                pool(
                        RouteStrategy.WEIGHTED,
                        candidate("a", weight = 3),
                        candidate("b", weight = 1)
                )
        val decisions =
                (1..4).map { RouteSelector.selectDecision(FunctionType.AUDIO_RECOGNITION, p) }
        val picks = decisions.map { it.candidate?.configId }
        assertEquals(3, picks.count { it == "a" })
        assertEquals(1, picks.count { it == "b" })
        assertEquals(2, decisions.map { it.pickedIndex }.toSet().size)
        assertTrue(decisions.last().reason.contains("WEIGHTED"))
    }

    @Test
    fun emptyPool_reportsNullCandidateWithoutThrowing() {
        val decision =
                RouteSelector.selectDecision(
                        FunctionType.VIDEO_RECOGNITION,
                        pool(RouteStrategy.ROUND_ROBIN)
                )
        assertNull(decision.candidate)
        assertEquals(0, decision.enabledCandidates)
        assertEquals(-1, decision.pickedIndex)
        assertTrue(decision.reason.contains("没有启用"))
    }

    @Test
    fun allDisabled_reportsNullCandidate() {
        val p =
                pool(
                        RouteStrategy.FIXED,
                        candidate("a", enabled = false),
                        candidate("b", enabled = false)
                )
        val decision = RouteSelector.selectDecision(FunctionType.IMAGE_RECOGNITION, p)
        assertNull(decision.candidate)
        assertEquals(0, decision.enabledCandidates)
    }

    @Test
    fun peekDecision_isStableUntilSelectAdvances() {
        val p = pool(RouteStrategy.ROUND_ROBIN, candidate("a"), candidate("b"))
        val type = FunctionType.ROLE_RESPONSE_PLANNER
        val preview = RouteSelector.peekDecision(type, p).pickedIndex
        // 连拍两次 preview 不变：peek 不推进游标
        assertEquals(preview, RouteSelector.peekDecision(type, p).pickedIndex)
        // select 落点与 preview 一致
        assertEquals(preview, RouteSelector.selectDecision(type, p).pickedIndex)
        // 推进后 preview 指向下一个
        val next = RouteSelector.peekDecision(type, p).pickedIndex
        assertTrue(next != preview)
    }

    @Test
    fun selectBackwardCompat_matchesDecisionCandidate() {
        val p = pool(RouteStrategy.FIXED, candidate("only"))
        val decision = RouteSelector.selectDecision(FunctionType.AUDIO_RECOGNITION, p)
        assertEquals(decision.candidate, RouteSelector.select(FunctionType.AUDIO_RECOGNITION, p))
        assertNotNull(decision.candidate)
    }
}