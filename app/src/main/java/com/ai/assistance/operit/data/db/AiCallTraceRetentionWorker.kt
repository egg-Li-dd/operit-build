package com.ai.assistance.operit.data.db

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ai.assistance.operit.data.dao.AiCallTraceDao
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * 调用链保留 Worker。
 *
 * 定位：**旁路维护**。只做两件事 —— 删掉 cutoff 之前的 trace，再清理失去父记录的孤儿 span。
 * 与业务调用流完全解耦：失败只会让 WorkManager 重试，不影响任何请求。
 */
class AiCallTraceRetentionWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "AiCallTraceRetention"

        /** 默认保留 7 天（见 05 决策矩阵方案 A）。 */
        const val DEFAULT_RETENTION_DAYS = 7L

        /** 入参：保留天数。 */
        const val KEY_RETENTION_DAYS = "retention_days"

        /** 入参：显式 cutoff（毫秒）。>= 0 时优先于 retention_days，供手工触发。 */
        const val KEY_CUTOFF_MS = "cutoff_ms"
    }

    override suspend fun doWork(): Result {
        val retentionDays = inputData.getLong(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)
        val explicitCutoff = inputData.getLong(KEY_CUTOFF_MS, AiCallTraceRetention.EXPLICIT_CUTOFF_UNSET)
        val cutoff =
            if (explicitCutoff >= 0L) {
                explicitCutoff
            } else {
                AiCallTraceRetention.cutoffFor(System.currentTimeMillis(), retentionDays)
            }

        return try {
            val dao = AppDatabase.getDatabase(applicationContext).aiCallTraceDao()
            val result = AiCallTraceRetention.purge(dao, cutoff)
            AppLogger.d(
                TAG,
                "调用链清理完成：cutoff=$cutoff，删除 trace=${result.traces}，孤儿 span=${result.spans}"
            )
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "调用链清理失败，交给 WorkManager 重试（旁路维护，不影响业务）", e)
            Result.retry()
        }
    }
}

/** 一次清理的计数结果。 */
internal data class AiCallTraceRetentionResult(val traces: Int, val spans: Int)

/**
 * 调用链清理的纯逻辑，从 [AiCallTraceRetentionWorker] 抽出以便单元测试。
 *
 * 不依赖 Android / WorkManager / Room 运行时：只要给一个 [AiCallTraceDao] 就能断言行为。
 */
internal object AiCallTraceRetention {

    /** [AiCallTraceRetentionWorker.KEY_CUTOFF_MS] 未设置时的哨兵值。 */
    const val EXPLICIT_CUTOFF_UNSET = -1L

    /** 由「当前时间 + 保留天数」推导 cutoff；负天数按 0 处理（等价于清空）。 */
    fun cutoffFor(nowMillis: Long, retentionDays: Long): Long =
        nowMillis - TimeUnit.DAYS.toMillis(retentionDays.coerceAtLeast(0L))

    /**
     * 删除 [cutoff] 之前的 trace，再清理其孤儿 span。
     *
     * 顺序不可颠倒：必须先删 trace，[AiCallTraceDao.deleteOrphanSpans] 才能识别出孤儿。
     */
    suspend fun purge(dao: AiCallTraceDao, cutoff: Long): AiCallTraceRetentionResult {
        val traces = dao.deleteTracesBefore(cutoff)
        val spans = dao.deleteOrphanSpans()
        return AiCallTraceRetentionResult(traces, spans)
    }
}
