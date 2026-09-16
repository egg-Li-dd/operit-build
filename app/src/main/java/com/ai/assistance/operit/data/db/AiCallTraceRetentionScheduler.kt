package com.ai.assistance.operit.data.db

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.TimeUnit

/**
 * 调用链保留调度器。
 *
 * 复用既有 WorkManager 范式（同 `WorkflowScheduler` / `RoomDatabaseBackupScheduler`），
 * 不引入新调度框架。默认每 1 天清理一次。
 *
 * 与 [AiCallTraceRetentionWorker] 拆分的原因：Worker 是纯清理，Scheduler 是纯编排，
 * 宿主（[com.ai.assistance.operit.core.application.OperitApplication]）只在启动时挂一次。
 */
object AiCallTraceRetentionScheduler {

    private const val TAG = "AiCallTraceRetention"

    /** 所有调度都会挂的 tag，供 [cancel] 统一收口。 */
    const val WORK_TAG = "ai_call_trace_retention"

    private const val UNIQUE_PERIODIC = "ai_call_trace_retention_periodic"
    private const val UNIQUE_ONE_TIME = "ai_call_trace_retention_one_time"

    /** 周期默认 1 天。 */
    const val DEFAULT_INTERVAL_DAYS = 1L

    /** WorkManager 周期下限归一化：请求 < 1 天按 1 天处理，避免误传 0。 */
    fun normalizeIntervalDays(requested: Long): Long = requested.coerceAtLeast(1L)

    private fun workManager(context: Context): WorkManager =
        WorkManager.getInstance(context.applicationContext)

    /** 立即（约束满足时）跑一次清理。 */
    fun scheduleOneTime(context: Context) {
        val request = OneTimeWorkRequestBuilder<AiCallTraceRetentionWorker>()
            .addTag(WORK_TAG)
            .build()
        workManager(context).enqueueUniqueWork(
            UNIQUE_ONE_TIME,
            ExistingWorkPolicy.REPLACE,
            request
        )
        AppLogger.d(TAG, "已调度一次性调用链清理")
    }

    /**
     * 注册 / 更新周期清理。
     *
     * 使用 [ExistingPeriodicWorkPolicy.KEEP]：已存在则保留原计时，避免每次冷启动重置周期。
     * 需要换参数（如保留天数变更）时先 [cancel] 再调用本方法。
     */
    fun schedulePeriodic(
        context: Context,
        intervalDays: Long = DEFAULT_INTERVAL_DAYS
    ) {
        val days = normalizeIntervalDays(intervalDays)
        val request = PeriodicWorkRequestBuilder<AiCallTraceRetentionWorker>(days, TimeUnit.DAYS)
            .addTag(WORK_TAG)
            .build()
        workManager(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        AppLogger.d(TAG, "已注册周期调用链清理，间隔=$days 天")
    }

    /** 取消全部调用链清理任务。 */
    fun cancel(context: Context) {
        workManager(context).cancelAllWorkByTag(WORK_TAG)
        AppLogger.d(TAG, "已取消调用链清理调度")
    }
}
