# 记录器与选路注入 [DONE]

旧实现：

- `RouteSelector.select(functionType, pool)` 只返回 `FunctionRouteCandidate?`：能知道"选了谁"，说不出"为什么"。
- `MultiServiceManager.getOrCreateServiceForFunctionLocked` 选完候选就结束，没有任何观测痕迹；`ServiceLease.close()` 只做释放。
- 全仓库无记录器、无 export 出口。

意图修正：

- 选路核多返回一份**决策明细**，把"为什么是它"变成可持久化数据，而不是拼日志。
- 在"选路 → 租约"这一小段上挂旁路观测：租约拿到时开 trace/span，租约归还时收口。
- 观测必须**永远不影响调用结果**：设施缺席就静默丢弃，写库异常只记日志。

新实现：

选路决策明细（`RouteSelector.kt`）：

```kotlin
data class RouteDecision(
    val candidate: FunctionRouteCandidate?,
    val strategy: RouteStrategy,
    val enabledCandidates: Int,   // 过滤掉 enabled=false 之后的数量
    val pickedIndex: Int,         // 在"启用候选"列表中的下标；空池为 -1
    val reason: String            // 人话解释，含策略名与游标/票号
)
```

- `selectDecision(type, pool)`：推进游标，返回明细（原 `select` 语义）。`reason` 例：`"WEIGHTED：票号 7/12 命中启用候选第 2 位"`。
- `peekDecision(type, pool)`：同源判定，**不推进游标**，仅供界面预览；`reason` 带"（只读预览）"后缀。
- `select` / `peek` 保留为对 `selectDecision` / `peekDecision` 的取值包装，调用点零改动（向后兼容）。
- 私有 `weightedPickIndex(candidates, ticket)`：原 `weightedPick` 改为返回下标，好让 `pickedIndex` 与 `candidate` 同源，杜绝"返回的候选和解释的下标对不上"。

记录器（`api/chat/enhance/trace/`）：

- `AiCallTraceIds`：`trc_` / `spn_` 前缀 + UUID（去横线）。前缀便于日志里一眼分辨，也便于按前缀清理。
- `AiCallTraceSink`：落库出口接口（`upsertTrace` / `upsertSpan`）。抽象出来是为了单测用内存实现、未来换导出/上报宿主时不动调用点。
- `RoomAiCallTraceSink`：Room 实现，写入前统一过 `maskedForStorage()`。
- `AiCallTraceRecorder`（单例）：
  - `install(context)`：绑定 Room 出口。`synchronized` + `installed` 标志，**幂等**，可被多处重复调用。
  - `overrideSink(replacement)`：测试/备用宿主替换出口，传 `null` 卸载。
  - `startTrace` / `updateTrace` / `recordSpan`：全部 `dispatch` 到独立 `Dispatchers.IO` 作用域。
  - `dispatch` 在 sink 为 null 时直接 `return`（静默丢弃）；协程内 `try/catch` 只 `AppLogger.w`，绝不向调用方抛出。

注入点（`MultiServiceManager.kt`）：

```kotlin
// acquireServiceForFunction：租约拿到时开观测
val observation = startRoutingObservation(functionType, resolved.decision, managedService)
return ServiceLease(
    closeAction = { releaseLease(managedService); finishRoutingObservation(observation) },
    service = managedService.service,
    modelConfig = managedService.modelConfig,
    modelParameters = modelParameters,
    traceId = observation?.traceId
)
```

- `startRoutingObservation`：从 `ManagedService` 取 `config`，用 `getModelByIndex(config.modelName, pickedIndex)` 解析出**实际模型名**（而不是配置里的逗号串），生成一条 `trace`（`routePoolMode = true`、`spanCount = 1`、`status = RUNNING`）+ 一条 `PRIMARY` span（`attemptIndex = 0`），写入 Recorder。失败返回 `null`，只记日志。
- `finishRoutingObservation`：租约归还时补 `finishedAt` / `durationMs` 并收口为 `SUCCESS`。
- `ServiceLease` 新增 `traceId: String?`：给调用方一个关联日志的把手。**目前无消费方**——P1 业务层回填调用成败时才会用到。
- 副作用为零：观测代码不改变 `decision`、不改变缓存键、不触碰任何既有返回值。

绑定时机（`core/application/OperitApplication.kt`）：

```kotlin
// initializeMainApplicationLocked 内
AiCallTraceRecorder.install(applicationContext)
AppLogger.d(TAG, "【启动计时】AI 调用链记录器已绑定 - ...")
```

放在应用初始化里而非 `MultiServiceManager` 构造函数：记录器是进程级单例，绑定一次即可；`MultiServiceManager` 会被多处 new，放在它里面要么重复建 Room 实例，要么引入隐式依赖。

并发与顺序：

- 观测写入在 IO 作用域异步执行，顺序不保证严格串行 —— 同一 span 的 `RUNNING` 与终态更新都走 `upsert`（按主键 `REPLACE`），乱序到达时后写的覆盖先写的，最坏情况是终态被 `RUNNING` 覆盖。
- 已知边界：这是 P0 的取舍（不引入写队列）。真正需要严格序的 P1 若出现"终态被覆盖"的实测现象，再加单写者队列。见 05 决策矩阵。

验证：

- `RouteSelectorDecisionTest`（7 例）：三策略的 `strategy` / `pickedIndex` / `reason` 标签；空池与全禁用返回 `candidate = null` 且 `pickedIndex = -1`；`peek` 连拍不变、`select` 落点与 `peek` 一致、推进后 `peek` 指向下一个；`select` 与 `selectDecision().candidate` 等价（向后兼容）。
- 测试只做相对断言（循环比例、三点去重），不假设游标起点：游标按 `FunctionType` 进程内持有、与既有 `RouteSelectorTest` 共用，JUnit 方法顺序不保证。

> 状态：已完成。代码 = `AiCallTraceIds.kt`、`AiCallTraceSink.kt`、`RoomAiCallTraceSink.kt`、`AiCallTraceRecorder.kt`（新建）+ `RouteSelector.kt`（决策明细）+ `MultiServiceManager.kt`（观测注入）+ `OperitApplication.kt`（绑定）；静态核对通过，按仓库执行准则未运行构建与测试。