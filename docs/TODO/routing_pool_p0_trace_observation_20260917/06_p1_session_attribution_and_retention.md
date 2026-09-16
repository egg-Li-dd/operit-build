# P1：会话归属与调用链保留调度 [DONE]

旧实现：

- P0 落库的 `ai_call_trace.chatId` 恒为 `null`：功能级选路发生在 chat 上下文之外，`acquireServiceForFunction` 拿不到会话 id，按会话聚合 trace 的 UI 看不到分组。
- `deleteTracesBefore` / `deleteOrphanSpans` 只有 DAO 方法，没有任何调度方：长时间运行下 trace 库只增不减。

意图修正：

- 把 `chatId` 透传进功能级选路，让 trace 能按会话归属；仅观测用，不改选路语义。
- 给保留策略落一个真的调度方 —— 复用仓库既有 WorkManager 范式，不引入新框架。

新实现：

### 1. 会话归属透传（改 2 文件）

| 文件 | 改动 |
| --- | --- |
| `MultiServiceManager.kt` | `acquireServiceForFunction(functionType, chatId: String? = null)`；docstring 注明「仅观测用、不参与选路」；`startRoutingObservation` 同步扩展；entity 构造 `chatId = chatId?.takeIf { it.isNotBlank() }`（空白归一为 null） |
| `EnhancedAIService.kt` | `MessageExecutionContext` 加 `val chatId: String? = null`；`sendMessage` 构造处传 `chatId = chatId`；`getModelExecutionSnapshot` 调 `acquireServiceForFunction(functionType, context.chatId)` |

默认参数保证零调用方破坏：现有 `acquireServiceForFunction(functionType)` 调用行为与 P0 完全一致。

### 2. 调用链保留调度（新 2 文件 + 改 1 文件）

| 文件 | 职责 |
| --- | --- |
| `data/db/AiCallTraceRetentionWorker.kt` | `CoroutineWorker`；从 `inputData` 取保留天数（默认 7d）或显式 cutoff；调 `AiCallTraceRetention.purge`；成功 `Result.success()`，异常 `AppLogger.w` + `Result.retry()`。纯逻辑 `AiCallTraceRetention.purge/cutoffFor` 抽出以便单测 |
| `data/db/AiCallTraceRetentionScheduler.kt` | `scheduleOneTime` / `schedulePeriodic`（默认 1 天，`ExistingPeriodicWorkPolicy.KEEP`）/ `cancel`；tag `ai_call_trace_retention` |
| `core/application/OperitApplication.kt` | `AiCallTraceRecorder.install(...)` 之后紧接 `AiCallTraceRetentionScheduler.schedulePeriodic(...)`，同一同步块内保序 |

保留天数取 05 决策矩阵方案 A（7 天）。清空顺序固定为「先删 trace，再清孤儿 span」—— 顺序颠倒会让 `deleteOrphanSpans` 找不到孤儿。

> 更正：05 移交表原写「加 `AppScheduler.traceRetentionWorker`」。仓库**不存在** `AppScheduler`。实际实现走既有 WorkManager 范式，见上表。

验证：

| 场景 | 输入 | 期望 |
| --- | --- | --- |
| 会话归属透传 | 有 chatId 的 `sendMessage` | trace.chatId = 传入值 |
| 空白 chatId 归一 | chatId = `""` / `" "` | 落库为 null，不写空串 |
| 缺省调用兼容 | `acquireServiceForFunction(functionType)` | 与 P0 行为一致，chatId 为 null |
| 清理计数 | fake dao 返回 (3, 5) | `purge` 回传 (3, 5) |
| 清理顺序 | 单次 `purge` | 调用序列 = `[deleteTracesBefore, deleteOrphanSpans]` |
| cutoff 推导 | now, 7d | `now - 7d`；负天数按 0 |
| 周期归一 | 0 / -5 | 1 |
| 默认值一致 | worker / scheduler | 7d / 1d |

红线：

- `chatId` 仅观测用：不改 `RouteSelector`、不改 `selectDecision` 返回值、不改 `ModelExecutionSnapshot` 对外契约、不改缓存键 / 工厂 / 限流。
- retention 是纯旁路：失败只 `Result.retry()`，不进业务流；调度注册失败不影响启动。
- 不擅自改保留天数：7d 为草案，改参数走 `schedulePeriodic(intervalDays)`。
- 不在本阶段定义降级触发条件 —— 留给 P1 第二期（span.status / cost / tokens 回填）。

i18n：

- 无新增用户可见字符串（全部为日志与落库字段）。

测试：

- `AiCallTraceRetentionWorkerTest`（6 例）：计数 / 顺序 / cutoff 透传 / cutoff 推导 / 负天数 / 默认值。
- `AiCallTraceRetentionSchedulerTest`（5 例）：归一化 ×2 / 默认周期 / tag 契约 / 与 worker 默认值一致。

完成口径：

- 本阶段改动：新增 2 源码文件 + 1 修改 + 2 测试文件（11 例）+ 1 文档。按仓库 TODO 规范，`[DONE]` 已标。全部变更已落盘，按仓库执行准则未运行构建与测试。

移交 P1 第二期：

| 遗留 | 影响 | 建议 |
| --- | --- | --- |
| 降级回填 | P0/P1 的 span.status 仍为占位，成功/失败/成本未回填 | 业务层在真实调用返回处写 span.status / token 数 / 成本 |
| 严格写序 | 并发写库 `RUNNING` 覆盖终态 | 若需要严格序，上单写者队列 |