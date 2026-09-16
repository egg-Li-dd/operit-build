package com.ai.assistance.operit.data.db

/**
 * `ai_call_trace` / `ai_call_span` 的建表与索引语句。
 *
 * 单独抽出成对象，是为了让 Room 迁移脚本和单元测试共用同一份 DDL：
 * 任何一侧改了列，另一侧的守卫测试立刻失败。
 *
 * 列定义必须与 [com.ai.assistance.operit.data.model.AiCallTraceEntity] /
 * [com.ai.assistance.operit.data.model.AiCallSpanEntity] 严格一致，
 * 否则 Room 打开数据库时会因 schema 校验失败抛异常。
 */
object AiCallTraceSchema {

    const val TABLE_TRACE = "ai_call_trace"
    const val TABLE_SPAN = "ai_call_span"

    val createStatements: List<String> =
        listOf(
            """
            CREATE TABLE IF NOT EXISTS `ai_call_trace` (
                `traceId` TEXT NOT NULL,
                `chatId` TEXT,
                `entryFunctionType` TEXT NOT NULL,
                `routePoolMode` INTEGER NOT NULL,
                `strategy` TEXT NOT NULL,
                `startedAt` INTEGER NOT NULL,
                `finishedAt` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `selectedConfigId` TEXT,
                `selectedModelName` TEXT,
                `selectedProvider` TEXT,
                `spanCount` INTEGER NOT NULL,
                `degraded` INTEGER NOT NULL,
                `errorMessage` TEXT,
                PRIMARY KEY(`traceId`)
            )
            """
                .trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `ai_call_span` (
                `spanId` TEXT NOT NULL,
                `traceId` TEXT NOT NULL,
                `parentSpanId` TEXT,
                `tier` TEXT NOT NULL,
                `functionType` TEXT NOT NULL,
                `configId` TEXT,
                `modelName` TEXT,
                `provider` TEXT,
                `strategy` TEXT,
                `attemptIndex` INTEGER NOT NULL,
                `startedAt` INTEGER NOT NULL,
                `finishedAt` INTEGER NOT NULL,
                `durationMs` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `inputTokens` INTEGER NOT NULL,
                `outputTokens` INTEGER NOT NULL,
                `costMicros` INTEGER NOT NULL,
                `errorType` TEXT,
                `errorMessage` TEXT,
                PRIMARY KEY(`spanId`)
            )
            """
                .trimIndent()
        )

    val indexStatements: List<String> =
        listOf(
            "CREATE INDEX IF NOT EXISTS `index_ai_call_trace_chatId` ON `ai_call_trace` (`chatId`)",
            "CREATE INDEX IF NOT EXISTS `index_ai_call_trace_startedAt` ON `ai_call_trace` (`startedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_ai_call_span_traceId` ON `ai_call_span` (`traceId`)",
            "CREATE INDEX IF NOT EXISTS `index_ai_call_span_traceId_startedAt` ON `ai_call_span` (`traceId`, `startedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_ai_call_span_functionType` ON `ai_call_span` (`functionType`)"
        )

    val allStatements: List<String>
        get() = createStatements + indexStatements
}
