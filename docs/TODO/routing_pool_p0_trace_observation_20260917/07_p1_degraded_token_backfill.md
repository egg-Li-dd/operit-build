# P1 第二期：租约终态回填（token / status）[DONE]

旧实现：

- P0 落库的 `ai_call_span.status` 恒为 `RUNNING` 初值兜底后的 `SUCCESS`，`inputTokens` / `outputTokens` / `costMicros` 恒为 0：span 只有"选路决策 + 租约存活期"的语义，没有真实调用结果。
- 业务层确实知道结果 —— `AIService.onTokensUpdated` 每轮都会回报 token，异常捕获点也拿得到 `Throwable` —— 但这些数据只写到 `local` 计数（`currentRequest*TokenCount` / `_perRequestTokenCounts`）和 `apiPreferences`，**没有任何一条路径把它们交回观测侧**。缺口在数据出口，不在数据源。
- 06 把这条遗留原样移交下来：「业务层在真实调用返回处写 span.status / token 数 / 成本」。

调研结论（动刀前先证伪）：

| 假设 | 取证方式 | 结论 |
| --- | --- | --- |
| 仓库存在真实降级链，可在降级点回填 `DEGRADED` | 全仓 grep `degraded\|fallback\|WEAK` | **不成立**。60 处命中跨 20 文件，逐条排除后全部是 UI 文案、工具层术语或 P0 预留字段。`AiCallSpanStatus.DEGRADED` / `AiCallTraceStatus.DEGRADED` / `AiCallSpanTier.WEAK` / `AiCallTraceEntity.degraded` 均为**占位**，无任何写入方 |
| span 结束点唯一 | grep `ServiceLease` / `closeAction` / `finishRoutingObservation` | 成立。唯一租约持有者是 `EnhancedAIService.ModelExecutionSnapshot(lease)`；唯一收口是 `ServiceLease.closeAction` |
| token 有统一出口 | grep `onTokensUpdated` | 成立。11 个 provider 文件均经 `TokenCacheManager` 回调 `AIService.onTokensUpdated: suspend (input, cachedInput, output) -> Unit` |

结论：本阶段**不触发**降级语义，改为「在任意租约归还点回填 status / tokens / cost，并把 `DEGRADED` 留成待接的写入位」。降级链落地那天，只需在接管点把 `outcome.degraded` 置 true，无需再动观测层。

新实现：

### 1. 终态载体（新 1 文件）

| 文件 | 内容 |
| --- | --- |
| `api/chat/enhance/trace/AiCallLeaseOutcome.kt` | `data class AiCallLeaseOutcome(status / inputTokens / outputTokens / costMicros / errorType / errorMessage / degraded)`；两个纯函数 `AiCallLeaseOutcome?.applyToTrace(trace, finishedAt)` / `applyToSpan(span, finishedAt, durationMs)`；私有 `Long.clampToIntField()` |

设计要点：

- **可空接收者即兼容开关**。`null` 走 `?:` 分支退化为 P0 语义（`SUCCESS` / 零 token / 无错误），所以"不回填"与"回填"共用同一条收口路径，不需要分支。
- **钳制而非截断**。落库字段 `inputTokens` / `outputTokens` 是 `Int`，载体是 `Long`。越界向上钳到 `Int.MAX_VALUE`，负值归零 —— 避免静默溢出成负数统计。
- **纯函数**。观测写坏不该污染业务侧持有的快照，`copy()` 返回新对象，入参不动。

### 2. 收口改造（改 1 文件）

| 文件 | 改动 |
| --- | --- |
| `api/chat/enhance/MultiServiceManager.kt` | `ServiceLease.closeAction` 签名 `suspend () -> Unit` → `suspend (AiCallLeaseOutcome?) -> Unit`；新增 `@Volatile var outcome: AiCallLeaseOutcome? = null`；`close()` 改为 `closeAction(outcome)`；`finishRoutingObservation(observation, outcome)` 改为按终态写回；`RoutingObservation` 补 `val traceId` 访问器 |

```kotlin
suspend fun close() {
    if (closed.compareAndSet(false, true)) {
        closeAction(outcome)
    }
}
```
`AtomicBoolean` 保证幂等，`@Volatile` 保证跨线程可见性 —— 业务写入在协程、收口在 `finally`，不能靠 happens-before 的运气。

`acquireServiceForFunction` 的 `closeAction` 组装：
```kotlin
closeAction = { outcome ->
    releaseLease(managedService)
    finishRoutingObservation(observation, outcome)
}
```
`acquireServiceForConfig`（chat 覆盖配置路径）保持 `{ _ -> releaseLease(managedService) }` —— 该路径不参与路由池观测，无 trace 可写，但必须消费掉参数以匹配新签名。

### 3. 业务侧回填（改 1 文件）

| 文件 | 改动 |
| --- | --- |
| `api/chat/EnhancedAIService.kt` | `ModelExecutionSnapshot` 新增 `@Volatile inputTokens / outputTokens / cachedInputTokens / failed` + `errorType / errorMessage` 与 `accumulateTokens()` / `markFailed()` / `toLeaseOutcome()`；`releaseModelExecutionSnapshot` 归还前组装终态；两处 token 累加点 + 1 处异常捕获点注入 |

注入点（行号为落刀后实测）：

| 位置 | 注入 | 说明 |
| --- | --- | --- |
| 主链 token 累加 | `execContext.modelExecutionSnapshot?.accumulateTokens(...)` | 与 `accumulated*TokenCount` 同步，观测不额外造数据 |
| 工具链 token 累加 | `modelSnapshot.accumulateTokens(...)` | 同上，另一条执行路径 |
| 主链异常捕获 | `execContext.modelExecutionSnapshot?.markFailed(e)` | 只记首次失败，后续不覆盖根因 |

```kotlin
private suspend fun releaseModelExecutionSnapshot(context: MessageExecutionContext) {
    val snapshot = context.modelExecutionSnapshot ?: return
    context.modelExecutionSnapshot = null
    // P1 回填：归还前把租约内累计的 token / 终态写回，供观测收口落到 span（旁路，不影响业务）
    snapshot.lease.outcome = snapshot.toLeaseOutcome()
    snapshot.lease.close()
}
```
先把 `outcome` 写进租约、再 `close()` —— 顺序反了收口就只能拿到 null。

实证复核抓到并修掉的两处（原样记录，避免下期重犯）：

| 位置 | 问题 | 修复 |
| --- | --- | --- |
| `EnhancedAIService` 主链累加点 | `modelExecutionSnapshot` 是 `ModelExecutionSnapshot?`，直连成员调用是编译错误 | 补 `?.` |
| `MultiServiceManager.RoutingObservation` | P0 遗留：类只有 `trace` / `span`，而 `traceId = observation?.traceId` 已在用 —— 无人编译过所以没暴露 | 补 `val traceId: String get() = trace.traceId` |

验证：

| 场景 | 输入 | 期望 |
| --- | --- | --- |
| 不回填退化 | `outcome = null` | span/trace `status = SUCCESS`，token/cost 为 0，无错误，`degraded = false` |
| 正常回填 | 多轮 token 累加后归还 | span 落 `inputTokens` / `outputTokens` / `costMicros`，`finishedAt` / `durationMs` 正确 |
| 失败回填 | 主链抛异常 | 首次异常的 `errorType` / `errorMessage` 落 span，trace 同步 `FAILED` |
| 失败不覆盖根因 | 连续两次异常 | 只保留第一次的类型与消息 |
| 负数 token | `-5 / -1` | 归零，统计不变负 |
| 溢出 token | `Int.MAX_VALUE + 1024` | 钳到 `Int.MAX_VALUE`，不溢出成负 |
| 降级留位 | 显式 `degraded = true` | trace 标记为 true，span status 可写 `DEGRADED`（本期无调用方） |
| 纯函数 | 回填后查原快照 | 原对象 `status` 仍 `RUNNING`，字段未被就地改写 |
| close 幂等 | 连续 `close()` ×3 | 收口只触发一次 |
| 未参与观测的租约 | `acquireServiceForConfig` 路径 | `traceId` 为 null，收口无 trace 可写，不抛异常 |

红线：

- 不改选路语义：`selectDecision` 返回值、`RouteSelector`、`RouteStrategy` 一律未动。
- 不改缓存键 / 工厂分派 / 限流维度：`outcome` 与 `traceId` 都只是租约上的只读旁路字段。
- `null` 终态必须与 P0 行为逐字段一致 —— 这是"可回滚"的判据：删掉注入不改变任何既有输出。
- 观测写库失败只 `AppLogger.w`，不进业务流（收口已有的 `try/catch`）。
- 本期**不启用** `DEGRADED`：仅在数据模型与纯函数层留位。定义降级触发条件属于降级链任务，不属于观测任务。

i18n：

- 无新增用户可见字符串（全部为日志与落库字段）。

测试：

- `AiCallLeaseOutcomeTest`（7 例）：P0 退化 / 正常回填 / 失败回填 / 负 token / 越界钳制 / degraded 只由显式标记驱动 / 纯函数不改入参。
- `ServiceLeaseOutcomeTest`（5 例）：终态透传 / 未回填传 null / close 幂等 / traceId 透传 / 非观测租约 traceId 为 null。用最小 `AIService` 桩占位，不触碰真实调用。

完成口径：

- 本阶段改动：新增 2 源码文件 + 2 修改 + 2 测试文件（12 例）+ 1 文档。按仓库 TODO 规范，`[DONE]` 已标。全部变更已落盘，按仓库执行准则未运行构建与测试。
- 实证复核已执行：新增文件落盘、4 处注入点行号实测、实体字段类型（`Int` vs `Long`）逐字段比对、`AiCallTraceRecorder` 方法签名与调用一致、`ServiceLease` 无外部构造方（签名变更零破坏）。

移交：

| 遗留 | 影响 | 建议 |
| --- | --- | --- |
| `DEGRADED` 无写入方 | 降级链路对界面仍不可见 | 降级链落地时在接管点置 `outcome.degraded = true` + `status = DEGRADED`，观测层零改动 |
| `costMicros` 恒 0 | 成本展示不可用 | 等 provider 侧暴露单价 / 用量换算再填，载体已就位 |
| `cachedInputTokens` 未落库 | 缓存命中率不可见 | 载体已累计；需 `ai_call_span` 加列 + Room 迁移，属数据模型变更 |
| 严格写序 | 并发写库 `RUNNING` 覆盖终态 | 若需要严格序，上单写者队列（见 03 / 06） |
