package com.ai.assistance.operit.api.chat.enhance.trace

import android.content.Context
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.AiCallSpanEntity
import com.ai.assistance.operit.data.model.AiCallTraceEntity
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 调用链记录器。
 *
 * 定位：**旁路观测**。它读不到选路结果以外的任何东西，也不参与任何决策。
 *
 * 关键约束：
 * - 未绑定 sink 时静默丢弃，不报错、不阻塞：观测设施缺席不该影响主流程。
 * - 写入在独立 IO 作用域，异常只记日志。调用链写失败必须与业务结果解耦。
 */
object AiCallTraceRecorder {

    private const val TAG = "AiCallTraceRecorder"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var sink: AiCallTraceSink? = null

    @Volatile private var installed = false

    /** 绑定 Room 出口。幂等，可被多个 MultiServiceManager 实例重复调用。 */
    fun install(context: Context) {
        synchronized(this) {
            if (installed) return
            sink =
                RoomAiCallTraceSink(
                    AppDatabase.getDatabase(context.applicationContext).aiCallTraceDao()
                )
            installed = true
            AppLogger.d(TAG, "调用链记录器已绑定 Room 出口")
        }
    }

    /** 测试/备用宿主替换出口；传 null 表示卸载。 */
    fun overrideSink(replacement: AiCallTraceSink?) {
        synchronized(this) {
            sink = replacement
            installed = replacement != null
        }
    }

    fun startTrace(trace: AiCallTraceEntity) {
        dispatch { it.upsertTrace(trace.maskedForStorage()) }
    }

    fun updateTrace(trace: AiCallTraceEntity) {
        dispatch { it.upsertTrace(trace.maskedForStorage()) }
    }

    fun recordSpan(span: AiCallSpanEntity) {
        dispatch { it.upsertSpan(span.maskedForStorage()) }
    }

    private fun dispatch(write: suspend (AiCallTraceSink) -> Unit) {
        val current = sink ?: return
        scope.launch {
            try {
                write(current)
            } catch (e: Exception) {
                AppLogger.w(TAG, "调用链写入失败（旁路观测，不影响调用结果）", e)
            }
        }
    }
}