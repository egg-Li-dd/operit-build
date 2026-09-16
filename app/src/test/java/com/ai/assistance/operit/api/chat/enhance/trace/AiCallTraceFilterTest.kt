package com.ai.assistance.operit.api.chat.enhance.trace

import com.ai.assistance.operit.data.model.AiCallTraceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 过滤条件归一化的契约。
 *
 * 这里的每个用例都对应一个"会把查询玩坏"的输入：空白串会被当成合法 chatId 导致永远查不到；
 * 大小写不一致会让 `success` 匹配不到 `SUCCESS`。归一化只做清洗，不做合法性判定 —— 那是执行器的事。
 */
class AiCallTraceFilterTest {

    @Test
    fun allowedStatuses_matchTraceStatusConstants() {
        assertEquals(
                setOf(
                        AiCallTraceStatus.RUNNING,
                        AiCallTraceStatus.SUCCESS,
                        AiCallTraceStatus.FAILED,
                        AiCallTraceStatus.DEGRADED
                ),
                AiCallTraceFilter.ALLOWED_STATUSES
        )
    }

    @Test
    fun isAllowedStatus_rejectsTyposAndCase() {
        assertTrue(AiCallTraceFilter.isAllowedStatus("SUCCESS"))
        assertFalse(AiCallTraceFilter.isAllowedStatus("SUCCES"))
        assertFalse(AiCallTraceFilter.isAllowedStatus("success"))
        assertFalse(AiCallTraceFilter.isAllowedStatus(""))
    }

    @Test
    fun normalizeChatId_blankBecomesNull() {
        assertNull(AiCallTraceFilter.normalizeChatId(null))
        assertNull(AiCallTraceFilter.normalizeChatId(""))
        assertNull(AiCallTraceFilter.normalizeChatId("   "))
        assertEquals("chat_1", AiCallTraceFilter.normalizeChatId("  chat_1 "))
    }

    @Test
    fun normalizeStatus_trimsAndUppercases() {
        assertNull(AiCallTraceFilter.normalizeStatus(null))
        assertNull(AiCallTraceFilter.normalizeStatus("  "))
        assertEquals("SUCCESS", AiCallTraceFilter.normalizeStatus(" success "))
        assertEquals("DEGRADED", AiCallTraceFilter.normalizeStatus("degraded"))
    }

    @Test
    fun normalizeStatus_doesNotValidate() {
        // 归一化不是校验：拼错的照样原样返回，交给执行器显式报错，绝不静默当"查不到"。
        assertEquals("BOGUS", AiCallTraceFilter.normalizeStatus("bogus"))
    }
}
