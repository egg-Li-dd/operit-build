# 数据模型与 Room 迁移 [DONE]

旧实现：

- 无调用链表。`AppDatabase` 版本 `20`，实体列表三项。
- 观测能力仅限 `AppLogger` 文本日志，进程结束即消失，无法回答"上一次调用到底走了谁"。

意图修正：

- 一次功能级请求 = 一条 trace，trace 下挂 0..n 条 span。数据库只存平铺结果，父子关系靠 `parentSpanId` 表达。
- 枚举一律以 `String` 落库（取 Kotlin 侧 `name`），与仓库既有实体风格一致。
- 迁移 DDL 与实体定义抽到同一个对象里，杜绝"实体改了列、迁移忘了改"。

新实现：

`AiCallTraceEntity`（表 `ai_call_trace`）：

```kotlin
@PrimaryKey val traceId: String,
val chatId: String? = null,            // 当前恒为 null，P1 透传
val entryFunctionType: String,          // FunctionType.name
val routePoolMode: Boolean = false,
val strategy: String,                   // RouteStrategy.name
val startedAt: Long,
val finishedAt: Long = 0L,              // 未结束为 0
val status: String = AiCallTraceStatus.RUNNING,
val selectedConfigId: String? = null,   // "这次到底用了谁"
val selectedModelName: String? = null,
val selectedProvider: String? = null,
val spanCount: Int = 0,
val degraded: Boolean = false,          // P1 的降级标记，P0 恒 false
val errorMessage: String? = null        // 已脱敏
```

索引：`chatId`、`startedAt`。

`AiCallSpanEntity`（表 `ai_call_span`）：

```kotlin
@PrimaryKey val spanId: String,
val traceId: String,
val parentSpanId: String? = null,
val tier: String,                       // PRIMARY / SUB / WEAK
val functionType: String,
val configId: String? = null,
val modelName: String? = null,
val provider: String? = null,
val strategy: String? = null,
val attemptIndex: Int = 0,              // 同父子下的第几次尝试，从 0 开始
val startedAt: Long,
val finishedAt: Long = 0L,
val durationMs: Long = 0L,
val status: String = AiCallSpanStatus.RUNNING,
val inputTokens: Int = 0,
val outputTokens: Int = 0,
val costMicros: Long = 0L,              // 成本位预留，未知为 0
val errorType: String? = null,          // 如 HttpStatusCodeException
val errorMessage: String? = null        // 已脱敏
```

索引：`traceId`、`(traceId, startedAt)`、`functionType`。

枚举（常量对象，不用 Kotlin enum，避免 Room 转换器）：

- `AiCallTraceStatus`：`RUNNING` / `SUCCESS` / `FAILED` / `DEGRADED`
- `AiCallSpanTier`：`PRIMARY` / `SUB` / `WEAK`
- `AiCallSpanStatus`：`RUNNING` / `SUCCESS` / `FAILED` / `SKIPPED` / `DEGRADED`

DDL 收敛（`data/db/AiCallTraceSchema.kt`）：

- `createStatements`（2 条建表）+ `indexStatements`（5 条索引）= `allStatements`。
- 迁移脚本与单元测试共用这一份 DDL；`AiCallTraceSchemaGuardTest` 逐列比对实体字段与 SQL 列，并校验索引名符合 Room 的 `index_<表>_<列>[_<列>...]` 生成规则（拼错就是在用户真机上炸，且编译期发现不了）。
- 全部语句带 `IF NOT EXISTS`，重复执行安全。

迁移与 `AppDatabase`：

```kotlin
version = 21,
entities = [..., AiCallTraceEntity::class, AiCallSpanEntity::class]

/** 20 -> 21：新增 AI 调用链两张表（trace / span）及其索引。DDL 与实体定义共用 AiCallTraceSchema。 */
private val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        AiCallTraceSchema.allStatements.forEach { db.execSQL(it) }
    }
}
// .addMigrations(..., MIGRATION_19_20, MIGRATION_20_21)
```

DAO（`data/dao/AiCallTraceDao.kt`）：

- 写入只有 upsert（`OnConflictStrategy.REPLACE`）：span 先落 `RUNNING`，结束时原地更新，不产生中间态垃圾。
- 读：`observeRecentTraces(limit)`、`observeTracesForChat(chatId, limit)`、`observeSpans(traceId)`、`getTrace(traceId)`、`getSpans(traceId)`。
- 清理：`deleteTracesBefore(cutoff)`、`deleteOrphanSpans()`、`clearTraces()`。P0 只备工具，不接调度（见 05）。

兼容 / 回滚：

- 迁移为纯新增，不动既有三张表的任何列。升级路径 `20 -> 21` 单步直达，无需数据修复。
- 回滚：代码回退到 v20 即可；`ai_call_trace` / `ai_call_span` 残留不被旧版本读取，Room 在降级时会因版本不匹配重建库（既有行为），调用链数据属于观测数据，可接受丢失。

验证：

- `AiCallTraceSchemaGuardTest`（4 例）：两表列集合与实体字段严格相等、主键正确、5 条索引名符合 Room 命名约定、DDL 幂等且 `allStatements` = 建表 + 索引。

> 状态：已完成。代码 = `AiCallTraceEntity.kt`、`AiCallSpanEntity.kt`、`AiCallTraceSchema.kt`、`AiCallTraceDao.kt`（均新建）+ `AppDatabase.kt`（版本 20 -> 21、迁移注册、实体注册）；静态核对通过，按仓库执行准则未运行构建与测试。