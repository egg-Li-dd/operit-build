package com.ai.assistance.operit.core.tools.defaultTool.standard

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.trace.AiCallTraceFilter
import com.ai.assistance.operit.api.chat.enhance.trace.AiCallTraceQuery
import com.ai.assistance.operit.api.chat.enhance.trace.AiCallTraceTextFormat
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.ToolExecutor
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.runBlocking

/**
 * 调用链观测的只读工具出口。
 *
 * 定位：让模型能回答"上一次到底是什么模型、成没成、花了多少 token"。**纯只读** ——
 * 没有写路径、没有删除路径，也不触发任何观测行为（查观测不该产生观测）。
 *
 * 三条硬约束：
 * - 有界：条数与 span 节点数都在 [AiCallTraceQuery] 里钳死，模型没法要全量。
 * - 显式报错：参数非法就报错，不静默降级成"查不到"。`status=SUCCES` 这种拼错必须回错误。
 * - 脱敏在出口：渲染文本时再过一次（见 [AiCallTraceTextFormat]）。
 *
 * 主构造直接吃 [AiCallTraceQuery]，是为了单测能注入替身 —— 否则一切单测都要拉起 Room。
 */
class StandardAiCallTraceToolExecutor(private val query: AiCallTraceQuery) : ToolExecutor {

    /** 生产入口：按 applicationContext 取 DAO。 */
    constructor(context: Context) :
            this(AiCallTraceQuery(AppDatabase.getDatabase(context.applicationContext).aiCallTraceDao()))

    companion object {
        private const val TAG = "AiCallTraceTool"

        const val TOOL_QUERY = "query_call_traces"
        const val TOOL_DETAIL = "get_call_trace_detail"
    }

    override fun invoke(tool: AITool): ToolResult =
            runBlocking {
                when (tool.name) {
                    TOOL_QUERY -> executeQueryTraces(tool)
                    TOOL_DETAIL -> executeTraceDetail(tool)
                    else ->
                            ToolResult(
                                    toolName = tool.name,
                                    success = false,
                                    result = StringResultData(""),
                                    error = "Unknown tool: ${tool.name}"
                            )
                }
            }

    private suspend fun executeQueryTraces(tool: AITool): ToolResult {
        val limitRaw = tool.parameters.find { it.name == "limit" }?.value?.trim().orEmpty()
        val limit = if (limitRaw.isEmpty()) null else limitRaw.toIntOrNull()
        if (limitRaw.isNotEmpty() && limit == null) {
            return failure(tool, "Invalid limit. Expected an integer.")
        }

        val chatId = AiCallTraceFilter.normalizeChatId(tool.parameters.find { it.name == "chat_id" }?.value)
        val status = AiCallTraceFilter.normalizeStatus(tool.parameters.find { it.name == "status" }?.value)
        if (status != null && !AiCallTraceFilter.isAllowedStatus(status)) {
            return failure(
                    tool,
                    "Invalid status. Expected one of: " +
                            AiCallTraceFilter.ALLOWED_STATUSES.sorted().joinToString(", ")
            )
        }

        AppLogger.d(
                TAG,
                "Query call traces: chatId=${chatId ?: "any"}, status=${status ?: "any"}, limit=${limit ?: "default"}"
        )

        return try {
            val traces = query.recentTraces(AiCallTraceFilter(chatId, status), limit)
            ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData(AiCallTraceTextFormat.formatTraceList(traces))
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "查询调用链失败（只读观测）", e)
            failure(tool, "Failed to query call traces: ${e.message}")
        }
    }

    private suspend fun executeTraceDetail(tool: AITool): ToolResult {
        val traceId = tool.parameters.find { it.name == "trace_id" }?.value?.trim()
        if (traceId.isNullOrEmpty()) {
            return failure(tool, "trace_id parameter is required")
        }

        AppLogger.d(TAG, "Get call trace detail: $traceId")

        return try {
            val detail = query.traceDetail(traceId)
            if (detail == null) {
                return failure(tool, "Call trace not found: $traceId (it may have been purged by retention)")
            }
            ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData(AiCallTraceTextFormat.formatTraceDetail(detail))
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "读取调用链详情失败（只读观测）", e)
            failure(tool, "Failed to read call trace detail: ${e.message}")
        }
    }

    private fun failure(tool: AITool, error: String): ToolResult =
            ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = error
            )
}