package com.ai.assistance.operit.util

/**
 * 观测数据落库前的敏感信息脱敏。
 *
 * 只处理“可能夹带凭据的自由文本”（错误信息、异常栈、被拼进日志的 URL），
 * 不碰结构化字段：结构化字段（configId、modelName）本身不含凭据。
 *
 * 目标：信息界面能看到“失败原因”，但看不到 apiKey / token。
 */
object SensitiveMasking {

    private const val MASK = "***"

    /** `Bearer xxxxx` 形式的 Authorization 头。 */
    private val BEARER_TOKEN = Regex("""(?i)\bBearer\s+[A-Za-z0-9._\-]{4,}""")

    /** `api_key=xxx` / `access_token: xxx` / `password=xxx` 形式的具名凭据，URL query 同样命中。 */
    private val NAMED_SECRET =
        Regex("""(?i)\b(api[_-]?key|apikey|access[_-]?token|accesstoken|secret|password|passwd)\b\s*[:=]\s*[^\s"',&]+""")

    /** `sk-xxxx` / `rk-xxxx` / `pk-xxxx` 形式的厂商 key，保留前缀便于识别来源。 */
    private val PREFIXED_KEY = Regex("""\b(?:sk|rk|pk)-[A-Za-z0-9_\-]{4,}""")

    /** 脱敏；null 与空串原样返回，避免把“无内容”渲染成 `***`。 */
    fun mask(text: String?): String? {
        if (text.isNullOrEmpty()) return text
        var masked = BEARER_TOKEN.replace(text, "Bearer $MASK")
        masked = NAMED_SECRET.replace(masked) { match -> match.groupValues[1] + "=" + MASK }
        masked = PREFIXED_KEY.replace(masked) { match -> match.value.take(3) + MASK }
        return masked
    }
}
