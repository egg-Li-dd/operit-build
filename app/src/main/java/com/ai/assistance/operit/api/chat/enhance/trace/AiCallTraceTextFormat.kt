package com.ai.assistance.operit.api.chat.enhance.trace

import com.ai.assistance.operit.data.model.AiCallSpanStatus
import com.ai.assistance.operit.data.model.AiCallTraceEntity
import com.ai.assistance.operit.util.SensitiveMasking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 调用链的文本渲染。
 *
 * 定位：**只读出口的最后一跳**。数据一旦渲染成文本就要进模型上下文，所以这里做两件收尾：
 * - 边界收敛：条数与缩进都固定，脏数据（超长错误串、异常深的 span 链）不能把上下文撑爆；
 * - 出口脱敏：库里写入时已脱敏（见 [AiCallTraceMasking]），这里对自由文本字段再过一次。
 *   `SensitiveMasking.mask` 是幂等的，重复脱敏不会有二次损伤，但能兜住"历史脏数据"。
 *
 * 纯函数，无 Android 依赖，便于单测。时间格式化可注入，测试里给确定性实现。
 */
object AiCallTraceTextFormat {

    private const val INDENT = "  "
    private const val EMPTY_LIST = "No call traces recorded yet."
    private const val MAX_ERROR_CHARS = 200

    /** 默认时间格式：本地时区，秒级精度。SimpleDateFormat 非线程安全，故每次现建。 */
    fun defaultTimeFormatter(): (Long) -> String = { epochMs ->
        if (epochMs <= 0L) "-"
        else
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .apply { timeZone = TimeZone.getDefault() }
                        .format(Date(epochMs))
    }

    /** 列表视图：一条一行，按传入顺序（DAO 已按 startedAt 倒序）。 */
    fun formatTraceList(
            traces: List<AiCallTraceEntity>,
            formatTime: (Long) -> String = defaultTimeFormatter()
    ): String {
        if (traces.isEmpty()) return EMPTY_LIST

        return buildString {
            appendLine("Call traces: ${traces.size} (newest first)")
            traces.forEachIndexed { index, trace ->
                appendLine(formatTraceLine(index + 1, trace, formatTime))
            }
        }.trimEnd()
    }

    /** 详情视图：trace 头部 + span 树。 */
    fun formatTraceDetail(
            detail: AiCallTraceDetail,
            formatTime: (Long) -> String = defaultTimeFormatter()
    ): String {
        val trace = detail.trace
        return buildString {
            appendLine("Trace ${trace.traceId}")
            appendLine("${INDENT}status=${trace.status}${if (trace.degraded) " degraded=true" else ""}")
            appendLine(
                    "${INDENT}function=${trace.entryFunctionType} strategy=${trace.strategy} routePool=${trace.routePoolMode}"
            )
            appendLine(
                    "${INDENT}selected: config=${trace.selectedConfigId ?: "-"} model=${trace.selectedModelName ?: "-"} provider=${trace.selectedProvider ?: "-"}"
            )
            appendLine(
                    "${INDENT}chatId=${trace.chatId ?: "-"} started=${formatTime(trace.startedAt)} finished=${formatTime(trace.finishedAt)}"
            )
            masked(trace.errorMessage)?.let { appendLine("${INDENT}error=$it") }
            appendLine(
                    "${INDENT}spans=${detail.totalSpanCount} failed=${detail.tree.failedSpanCount} maxDepth=${detail.tree.maxDepth}"
            )
            if (detail.truncated) {
                appendLine(
                        "${INDENT}note=span nodes truncated to ${detail.tree.spanCount} of ${detail.totalSpanCount}"
                )
            }
            appendLine("Spans:")
            if (detail.tree.roots.isEmpty()) {
                appendLine("${INDENT}(none)")
            } else {
                detail.tree.roots.forEach { appendNode(this, it, 0) }
            }
        }.trimEnd()
    }

    private fun appendNode(builder: StringBuilder, node: AiCallSpanNode, depth: Int) {
        val span = node.span
        val prefix = INDENT.repeat(depth + 1)
        val attempt = if (span.attemptIndex > 0) " attempt=${span.attemptIndex}" else ""
        builder.appendLine(
                "$prefix${statusMark(span.status)} ${span.spanId} ${span.tier} ${span.functionType}" +
                        " ${span.status} in=${span.inputTokens} out=${span.outputTokens}" +
                        " cost=${span.costMicros} duration=${span.durationMs}ms" +
                        " model=${span.modelName ?: "-"}$attempt"
        )
        masked(span.errorMessage)?.let {
            val type = span.errorType ?: "error"
            builder.appendLine("$prefix$INDENT$type: $it")
        }
        node.children.forEach { appendNode(builder, it, depth + 1) }
    }

    private fun statusMark(status: String): String =
            when (status) {
                AiCallSpanStatus.FAILED -> "[!]"
                AiCallSpanStatus.DEGRADED,
                AiCallSpanStatus.SKIPPED -> "[~]"
                AiCallSpanStatus.RUNNING -> "[…]"
                else -> "[.]"
            }

    private fun formatTraceLine(
            ordinal: Int,
            trace: AiCallTraceEntity,
            formatTime: (Long) -> String
    ): String {
        val duration =
                if (trace.finishedAt > trace.startedAt) "${trace.finishedAt - trace.startedAt}ms" else "-"
        val degraded = if (trace.degraded) " degraded=true" else ""
        val error = masked(trace.errorMessage)?.let { " error=$it" }.orEmpty()
        return "$ordinal) ${trace.traceId} ${formatTime(trace.startedAt)} ${trace.entryFunctionType}" +
                " ${trace.strategy} ${trace.status}$degraded spans=${trace.spanCount} duration=$duration" +
                " model=${trace.selectedModelName ?: "-"} provider=${trace.selectedProvider ?: "-"}" +
                " config=${trace.selectedConfigId ?: "-"} chat=${trace.chatId ?: "-"}$error"
    }

    /** 出口脱敏：只处理自由文本字段（结构化字段不含凭据），并对超长错误串截断。 */
    internal fun masked(text: String?): String? {
        val cleaned = SensitiveMasking.mask(text)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (cleaned.length <= MAX_ERROR_CHARS) cleaned
        else cleaned.take(MAX_ERROR_CHARS) + "…"
    }
}