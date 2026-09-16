package com.ai.assistance.operit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脱敏契约：信息界面必须看得到"失败原因"，但看不到 apiKey / token。
 *
 * 这里守的是两个方向：该脱的必须脱干净；不该动的文本不能被误伤（正则过宽会把正常报错打成 `***`）。
 */
class SensitiveMaskingTest {

    @Test
    fun bearerToken_maskedKeepingScheme() {
        val masked = SensitiveMasking.mask("HTTP 401: Authorization: Bearer sk-abcdefghijklmnop")
        assertFalse(masked!!.contains("abcdefghijklmnop"))
        assertTrue(masked.contains("Bearer ***"))
    }

    @Test
    fun namedSecret_maskedKeepingKeyName() {
        val masked = SensitiveMasking.mask("POST https://api.example.com/v1?api_key=AKIA1234567890 failed")
        assertFalse(masked!!.contains("AKIA1234567890"))
        assertTrue(masked.contains("api_key=***"))
    }

    @Test
    fun prefixedVendorKey_maskedKeepingPrefix() {
        val masked = SensitiveMasking.mask("invalid key: sk-proj-AbCdEf123456")
        assertFalse(masked!!.contains("AbCdEf123456"))
        // 前缀保留：界面还能看出是哪家厂商的 key 配置错了
        assertTrue(masked.contains("sk-***"))
    }

    @Test
    fun nullAndEmpty_passThroughUntouched() {
        assertNull(SensitiveMasking.mask(null))
        assertEquals("", SensitiveMasking.mask(""))
    }

    @Test
    fun plainErrorMessage_notMutated() {
        val raw = "SocketTimeoutException: connect timed out after 30000ms, model=mimo-pro"
        assertEquals(raw, SensitiveMasking.mask(raw))
    }

    @Test
    fun similarLookingWords_notFalsePositive() {
        // "task=..." / "risk-averse" / "desk-lamp" 都不该被打码，否则正常日志会被吃掉
        listOf("task=done", "risk-averse", "desk-lamp").forEach { raw ->
            assertEquals(raw, SensitiveMasking.mask(raw))
        }
        // 裸 "Bearer" 后无凭据、或凭据过短（<4 位）时按原样保留
        assertEquals("Bearer", SensitiveMasking.mask("Bearer"))
    }

    @Test
    fun secretInStackTrace_masked() {
        val raw = "HttpStatusCodeException: {\"error\":{\"message\":\"Incorrect API key: sk-live-9f8e7d6c5b4a\"}}"
        val masked = SensitiveMasking.mask(raw)!!
        assertFalse(masked.contains("9f8e7d6c5b4a"))
        assertTrue(masked.contains("Incorrect API key"))
    }
}
