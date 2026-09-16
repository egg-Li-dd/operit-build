package com.ai.assistance.operit.data.db

import com.ai.assistance.operit.data.model.AiCallSpanEntity
import com.ai.assistance.operit.data.model.AiCallTraceEntity
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迁移 DDL 与 Room 实体的列一致性守卫。
 *
 * `AiCallTraceSchema` 的注释写着"任何一侧改了列，另一侧的守卫测试立刻失败"——本文件就是那个守卫。
 * 这类不一致不会在编译期暴露：改成 DDL 少一列，只有用户真机打开数据库时才会 schema 校验失败。
 */
class AiCallTraceSchemaGuardTest {

    private val columnRegex = Regex("`(\\w+)`\\s+(TEXT|INTEGER|REAL|BLOB)")

    private fun columnsOf(ddl: String): Set<String> =
            columnRegex.findAll(ddl).map { it.groupValues[1] }.toSet()

    private fun fieldNamesOf(clazz: Class<*>): Set<String> =
            clazz.declaredFields
                    .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
                    .map { it.name }
                    .toSet()

    private fun createStatementOf(table: String): String =
            AiCallTraceSchema.createStatements.first { it.contains("`$table`") }

    @Test
    fun traceTable_columnsMatchEntity() {
        val sqlColumns = columnsOf(createStatementOf(AiCallTraceSchema.TABLE_TRACE))
        val entityColumns = fieldNamesOf(AiCallTraceEntity::class.java)
        assertEquals(entityColumns, sqlColumns)
        assertTrue(createStatementOf(AiCallTraceSchema.TABLE_TRACE).contains("PRIMARY KEY(`traceId`)"))
    }

    @Test
    fun spanTable_columnsMatchEntity() {
        val sqlColumns = columnsOf(createStatementOf(AiCallTraceSchema.TABLE_SPAN))
        val entityColumns = fieldNamesOf(AiCallSpanEntity::class.java)
        assertEquals(entityColumns, sqlColumns)
        assertTrue(createStatementOf(AiCallTraceSchema.TABLE_SPAN).contains("PRIMARY KEY(`spanId`)"))
    }

    @Test
    fun indexNames_followRoomNamingConvention() {
        // Room 打开数据库时按 `index_<表>_<列>[_<列>...]` 核对索引名，拼错就是在真机上炸。
        val expected =
                listOf(
                        roomIndexName(AiCallTraceSchema.TABLE_TRACE, "chatId"),
                        roomIndexName(AiCallTraceSchema.TABLE_TRACE, "startedAt"),
                        roomIndexName(AiCallTraceSchema.TABLE_SPAN, "traceId"),
                        roomIndexName(AiCallTraceSchema.TABLE_SPAN, "traceId", "startedAt"),
                        roomIndexName(AiCallTraceSchema.TABLE_SPAN, "functionType")
                )
        assertEquals(expected.size, AiCallTraceSchema.indexStatements.size)
        expected.forEach { name ->
            assertTrue("缺少索引 $name", AiCallTraceSchema.indexStatements.any { it.contains("`$name`") })
        }
    }

    @Test
    fun ddl_isIdempotentAndCoversBothTables() {
        AiCallTraceSchema.createStatements.forEach { statement ->
            assertTrue(statement.contains("CREATE TABLE IF NOT EXISTS"))
        }
        AiCallTraceSchema.indexStatements.forEach { statement ->
            assertTrue(statement.contains("CREATE INDEX IF NOT EXISTS"))
        }
        assertEquals(
                AiCallTraceSchema.createStatements + AiCallTraceSchema.indexStatements,
                AiCallTraceSchema.allStatements
        )
    }

    private fun roomIndexName(table: String, vararg columns: String): String =
            (listOf("index", table) + columns).joinToString("_")
}