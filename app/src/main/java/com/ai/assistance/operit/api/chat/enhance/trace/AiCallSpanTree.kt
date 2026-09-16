package com.ai.assistance.operit.api.chat.enhance.trace

import com.ai.assistance.operit.data.model.AiCallSpanEntity
import com.ai.assistance.operit.data.model.AiCallSpanStatus

/** span 的树形节点。 */
data class AiCallSpanNode(val span: AiCallSpanEntity, val children: List<AiCallSpanNode>) {
    val spanId: String
        get() = span.spanId
}

/** 调用链的树形视图。root 为无父、或父不在本次集合内的 span。 */
data class AiCallSpanTree(val roots: List<AiCallSpanNode>) {

    val spanCount: Int = roots.sumOf { countOf(it) }

    val failedSpanCount: Int = roots.sumOf { failedCountOf(it) }

    val maxDepth: Int = roots.maxOfOrNull { depthOf(it) } ?: 0

    /** 深度优先展开，便于列表渲染。 */
    fun flatten(): List<AiCallSpanNode> = roots.flatMap { flatOf(it) }

    private fun countOf(node: AiCallSpanNode): Int = 1 + node.children.sumOf { countOf(it) }

    private fun failedCountOf(node: AiCallSpanNode): Int =
        (if (node.span.status == AiCallSpanStatus.FAILED) 1 else 0) +
            node.children.sumOf { failedCountOf(it) }

    private fun depthOf(node: AiCallSpanNode): Int =
        1 + (node.children.maxOfOrNull { depthOf(it) } ?: 0)

    private fun flatOf(node: AiCallSpanNode): List<AiCallSpanNode> =
        listOf(node) + node.children.flatMap { flatOf(it) }
}

/**
 * 把平铺的 span 还原成树。
 *
 * 三条规则，全部有测试兜底：
 * - 排序稳定：`startedAt` -> `attemptIndex` -> `spanId`，保证同一份数据渲染结果一致。
 * - 父缺失（父被清理或跨 trace 脏数据）时该 span 升为 root，绝不丢弃。
 * - 环检测：parent 链成环时，成环的边不采用、成员升为 root；父子关系可以少一层，span 不能丢，也不能递归爆栈。
 */
object AiCallSpanTreeBuilder {

    private val ORDER =
        compareBy<AiCallSpanEntity>({ it.startedAt }, { it.attemptIndex }, { it.spanId })

    fun build(spans: List<AiCallSpanEntity>): AiCallSpanTree {
        if (spans.isEmpty()) return AiCallSpanTree(emptyList())

        val byId = spans.associateBy { it.spanId }
        val childrenByParent = LinkedHashMap<String, MutableList<AiCallSpanEntity>>()
        val roots = mutableListOf<AiCallSpanEntity>()

        // 环成员缓存。脏数据（跨 trace、写坏 parentSpanId）可能让 parent 链成环，
        // 成环的边一律不采用，成员升为 root：宁可少一层父子关系，也不能丢 span 或递归爆栈。
        val cycleMembers = mutableSetOf<String>()
        val acyclic = mutableSetOf<String>()

        fun leadsIntoCycle(spanId: String): Boolean {
            val path = LinkedHashSet<String>()
            var current: String? = spanId
            while (current != null) {
                if (current in cycleMembers) return true
                if (current in acyclic) return false
                if (!path.add(current)) {
                    cycleMembers.addAll(path)
                    return true
                }
                current =
                    byId[current]?.parentSpanId?.let { parent ->
                        if (parent == current || !byId.containsKey(parent)) null else parent
                    }
            }
            acyclic.addAll(path)
            return false
        }

        spans.sortedWith(ORDER).forEach { span ->
            val parentId = span.parentSpanId
            val hasResolvableParent =
                parentId != null &&
                    parentId != span.spanId &&
                    byId.containsKey(parentId) &&
                    !leadsIntoCycle(span.spanId)
            if (hasResolvableParent) {
                childrenByParent.getOrPut(parentId) { mutableListOf() }.add(span)
            } else {
                roots.add(span)
            }
        }

        val activePath = mutableSetOf<String>()
        fun toNode(span: AiCallSpanEntity): AiCallSpanNode {
            if (!activePath.add(span.spanId)) return AiCallSpanNode(span, emptyList())
            val children = childrenByParent[span.spanId].orEmpty().map { toNode(it) }
            activePath.remove(span.spanId)
            return AiCallSpanNode(span, children)
        }

        return AiCallSpanTree(roots.map { toNode(it) })
    }
}