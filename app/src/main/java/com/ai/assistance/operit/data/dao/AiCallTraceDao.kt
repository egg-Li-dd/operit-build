package com.ai.assistance.operit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ai.assistance.operit.data.model.AiCallSpanEntity
import com.ai.assistance.operit.data.model.AiCallTraceEntity
import kotlinx.coroutines.flow.Flow

/** 调用链读写。写入全部为 upsert：span 先落 RUNNING，结束时原地更新。 */
@Dao
interface AiCallTraceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrace(trace: AiCallTraceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSpan(span: AiCallSpanEntity)

    @Query("SELECT * FROM ai_call_trace ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecentTraces(limit: Int): Flow<List<AiCallTraceEntity>>

    @Query("SELECT * FROM ai_call_trace WHERE traceId = :traceId LIMIT 1")
    suspend fun getTrace(traceId: String): AiCallTraceEntity?

    /**
     * 只读查询：按可选条件筛最近调用链。
     *
     * `chatId` / `status` 传 null 表示该条件不参与筛选（在 SQL 里判定，避免先 LIMIT 再过滤
     * 导致"有数据却返回空"）。limit 由调用方钳制，DAO 不信任入参。
     */
    @Query(
            """
            SELECT * FROM ai_call_trace
            WHERE (:chatId IS NULL OR chatId = :chatId)
              AND (:status IS NULL OR status = :status)
            ORDER BY startedAt DESC
            LIMIT :limit
            """
    )
    suspend fun queryTraces(chatId: String?, status: String?, limit: Int): List<AiCallTraceEntity>

    @Query("SELECT * FROM ai_call_trace WHERE chatId = :chatId ORDER BY startedAt DESC LIMIT :limit")
    fun observeTracesForChat(chatId: String, limit: Int): Flow<List<AiCallTraceEntity>>

    @Query("SELECT * FROM ai_call_span WHERE traceId = :traceId ORDER BY startedAt ASC, attemptIndex ASC, spanId ASC")
    fun observeSpans(traceId: String): Flow<List<AiCallSpanEntity>>

    @Query("SELECT * FROM ai_call_span WHERE traceId = :traceId ORDER BY startedAt ASC, attemptIndex ASC, spanId ASC")
    suspend fun getSpans(traceId: String): List<AiCallSpanEntity>

    @Query("DELETE FROM ai_call_trace WHERE startedAt < :cutoff")
    suspend fun deleteTracesBefore(cutoff: Long): Int

    @Query("DELETE FROM ai_call_span WHERE traceId NOT IN (SELECT traceId FROM ai_call_trace)")
    suspend fun deleteOrphanSpans(): Int

    @Query("DELETE FROM ai_call_trace")
    suspend fun clearTraces()
}