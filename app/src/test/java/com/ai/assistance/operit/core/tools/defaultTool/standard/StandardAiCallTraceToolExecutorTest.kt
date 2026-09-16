package com.ai.assistance.operit.core.tools.defaultTool.standard

import com.ai.assistance.operit.api.chat.enhance.trace.AiCallTraceQuery
import com.ai.assistance.operit.api.chat.enhance.trace.FakeAiCallTraceDao
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.AiCallTraceEntity
import com.ai.assistance.operit.data.model.AiCallTraceStatus
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.ToolParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只读工具出口的入参契约。
 *
 * 这里的核心断言是 **"参数非法必须显式报错"**：观测工具最怕的行为是"静默返回空结果"——
 * 模型会据此断定'没有调用记录'，而真相是它把 `status` 拼错了。所以每种非法输入都要有独立用例。
 *
 * 主构造直接吃 [AiCallTraceQuery]，测试无需拉起 Room。
 */
class StandardAiCallTraceToolExecutorTest {

    private fun daoWith(vararg traces: AiCallTraceEntity): FakeAiCallTraceDao {
        val dao = FakeAiCallTraceDao()
        traces.forEach { dao.seedTrace(it) }
        return dao
    }

    private fun executor(dao: FakeAiCallTraceDao) =
            StandardAiCallTraceToolExecutor(AiCallTraceQuery(dao))

    private fun trace(id: String = "trc_1", status: String = AiCallTraceStatus.SUCCESS) =
            AiCallTraceEntity(
                    traceId = id,
                    chatId = "chat_1",
                    entryFunctionType = FunctionType.CHAT.name,
                    strategy = "FIXED",
                    startedAt = 1000L,
                    finishedAt = 1200L,
                    status = status
            )

    private fun textOf(tool: String, vararg params: Pair<String, String>) =
            executor(daoWith(trace())).invoke(
                    AITool(name = tool, parameters = params.map { ToolParameter(it.first, it.second) })
            )

    @Test
    fun query_withNoFilters_succeedsAndRendersList() {
        val result = textOf(StandardAiCallTraceToolExecutor.TOOL_QUERY)

        assertTrue(result.success)
        assertNotNull(result.result)
        assertTrue((result.result as StringResultData).value.contains("trc_1"))
    }

    @Test
    fun query_withInvalidLimit_failsExplicitly() {
        val result = textOf(StandardAiCallTraceToolExecutor.TOOL_QUERY, "limit" to "abc")

        assertFalse(result.success)
        assertTrue(result.error!!.contains("limit"))
    }

    @Test
    fun query_withOutOfRangeLimit_stillSucceeds() {
        // 越界不是错误：钳制即可，绝不能把"想多要几条"变成失败。
        val result = textOf(StandardAiCallTraceToolExecutor.TOOL_QUERY, "limit" to "999999")

        assertTrue(result.success)
    }

    @Test
    fun query_withUnknownStatus_failsWithAllowedValues() {
        val result = textOf(StandardAiCallTraceToolExecutor.TOOL_QUERY, "status" to "SUCCES")

        assertFalse(result.success)
        assertTrue(result.error!!.contains("SUCCESS"))
    }

    @Test
    fun query_withLowercaseStatus_isAccepted() {
        val result = textOf(StandardAiCallTraceToolExecutor.TOOL_QUERY, "status" to "success")

        assertTrue(result.success)
    }

    @Test
    fun detail_withoutTraceId_failsExplicitly() {
        val result = textOf(StandardAiCallTraceToolExecutor.TOOL_DETAIL)

        assertFalse(result.success)
        assertTrue(result.error!!.contains("trace_id"))
    }

    @Test
    fun detail_withMissingTrace_failsInsteadOfEmptySuccess() {
        val result = textOf(StandardAiCallTraceToolExecutor.TOOL_DETAIL, "trace_id" to "nope")

        assertFalse(result.success)
        assertTrue(result.error!!.contains("not found"))
    }

    @Test
    fun detail_withExistingTrace_succeeds() {
        val result = textOf(StandardAiCallTraceToolExecutor.TOOL_DETAIL, "trace_id" to "trc_1")

        assertTrue(result.success)
        assertTrue((result.result as StringResultData).value.contains("Trace trc_1"))
    }

    @Test
    fun unknownTool_failsInsteadOfCrawling() {
        val result = textOf("not_a_trace_tool")

        assertFalse(result.success)
        assertTrue(result.error!!.contains("Unknown tool"))
    }

    @Test
    fun toolNames_areStable() {
        // 工具名是对外契约（提示词/JS 都按它寻址），改动必须显式引发测试失败。
        assertEquals("query_call_traces", StandardAiCallTraceToolExecutor.TOOL_QUERY)
        assertEquals("get_call_trace_detail", StandardAiCallTraceToolExecutor.TOOL_DETAIL)
    }
}