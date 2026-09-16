package com.ai.assistance.operit.api.chat.enhance.trace

import com.ai.assistance.operit.data.model.AiCallSpanEntity
import com.ai.assistance.operit.data.model.AiCallSpanStatus
import com.ai.assistance.operit.data.model.AiCallSpanTier
import com.ai.assistance.operit.data.model.FunctionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * span 树的兜底契约。数据库只存平铺结果，父子关系全靠这里还原：
 * 脏数据（父被清理、跨 trace、写坏 parentSpanId 成环）不能丢 span，也不能递归爆栈。
 */
class AiCallSpanTreeTest {

    private fun span(
            id: String,
            parent: String? = null,
            startedAt: Long = 0L,
            attempt: Int = 0,
            status: String = AiCallSpanStatus.SUCCESS
    ) =
            AiCallSpanEntity(
                    spanId = id,
                    traceId = "trc_test",
                    parentSpanId = parent,
                    tier = AiCallSpanTier.PRIMARY,
                    functionType = FunctionType.CHAT.name,
                    startedAt = startedAt,
                    attemptIndex = attempt,
                    status = status
            )

    @Test
    fun emptyInput_returnsEmptyTree() {
        val tree = AiCallSpanTreeBuilder.build(emptyList())
        assertTrue(tree.roots.isEmpty())
        assertEquals(0, tree.spanCount)
        assertEquals(0, tree.maxDepth)
    }

    @Test
    fun missingParent_promotesToRootWithoutLosingSpan() {
        val tree = AiCallSpanTreeBuilder.build(listOf(span("orphan", parent = "gone")))
        assertEquals(1, tree.roots.size)
        assertEquals("orphan", tree.roots.first().spanId)
        assertEquals(1, tree.spanCount)
    }

    @Test
    fun nestedSpans_reportDepthAndFlatten() {
        val tree =
                AiCallSpanTreeBuilder.build(
                        listOf(
                                span("root", startedAt = 1),
                                span("child", parent = "root", startedAt = 2),
                                span("grandchild", parent = "child", startedAt = 3)
                        )
                )
        assertEquals(1, tree.roots.size)
        assertEquals(3, tree.spanCount)
        assertEquals(3, tree.maxDepth)
        assertEquals(listOf("root", "child", "grandchild"), tree.flatten().map { it.spanId })
    }

    @Test
    fun siblings_sortedByStartedAtThenAttemptThenId() {
        val tree =
                AiCallSpanTreeBuilder.build(
                        listOf(
                                span("d", parent = "root", startedAt = 6, attempt = 1),
                                span("c", parent = "root", startedAt = 6, attempt = 1),
                                span("b", parent = "root", startedAt = 6, attempt = 0),
                                span("a", parent = "root", startedAt = 5, attempt = 1),
                                span("root", startedAt = 1)
                        )
                )
        // startedAt -> attemptIndex -> spanId，三级键都要生效，渲染顺序才稳定
        assertEquals(listOf("a", "b", "c", "d"), tree.roots.first().children.map { it.spanId })
    }

    @Test
    fun selfReferencingSpan_isRootAndCountedOnce() {
        val tree = AiCallSpanTreeBuilder.build(listOf(span("self", parent = "self")))
        assertEquals(1, tree.roots.size)
        assertEquals(1, tree.spanCount)
        assertEquals(1, tree.maxDepth)
    }

    @Test
    fun twoNodeCycle_keepsBothSpansWithoutDuplication() {
        // A.parent=B、B.parent=A：入口为空。旧实现对这种数据会整条丢光。
        val tree =
                AiCallSpanTreeBuilder.build(
                        listOf(span("A", parent = "B", startedAt = 1), span("B", parent = "A", startedAt = 2))
                )
        assertEquals(2, tree.spanCount)
        assertEquals(2, tree.roots.size)
        assertEquals(1, tree.maxDepth)
        assertEquals(setOf("A", "B"), tree.flatten().map { it.spanId }.toSet())
    }

    @Test
    fun cycleWithEntrance_keepsAllSpans() {
        // A -> B <-> C：A 也是 dirty data 的一部分，一并升为 root，但一个都不能少。
        val tree =
                AiCallSpanTreeBuilder.build(
                        listOf(
                                span("A", parent = "B", startedAt = 1),
                                span("B", parent = "C", startedAt = 2),
                                span("C", parent = "B", startedAt = 3)
                        )
                )
        assertEquals(3, tree.spanCount)
        assertEquals(setOf("A", "B", "C"), tree.flatten().map { it.spanId }.toSet())
    }

    @Test
    fun failedSpans_countedAcrossTree() {
        val tree =
                AiCallSpanTreeBuilder.build(
                        listOf(
                                span("root", startedAt = 1, status = AiCallSpanStatus.FAILED),
                                span("ok", parent = "root", startedAt = 2),
                                span("bad", parent = "root", startedAt = 3, status = AiCallSpanStatus.FAILED)
                        )
                )
        assertEquals(3, tree.spanCount)
        assertEquals(2, tree.failedSpanCount)
    }

    @Test
    fun deepChain_buildsWithoutStackOverflow() {
        val spans = (0 until 500).map { index ->
            span("n$index", parent = if (index == 0) null else "n${index - 1}", startedAt = index.toLong())
        }
        val tree = AiCallSpanTreeBuilder.build(spans)
        assertEquals(500, tree.spanCount)
        assertEquals(500, tree.maxDepth)
    }
}