# 选路核与管理器接入 [DONE]

旧实现：

- `MultiServiceManager.getOrCreateServiceForFunctionLocked(functionType)`（`MultiServiceManager.kt:129-150`）：
  - 先查 `serviceInstances[functionType]` 单槽缓存；
  - 取 `functionalConfigManager.getConfigMappingForFunction(functionType)`；
  - `createServiceFromConfig(config, configMapping.modelIndex)`；
  - 写回 `serviceInstances[functionType]`，CHAT 额外写 `defaultService`。
- `getOrCreateServiceForConfigLocked(configId, modelIndex)`（`152-167`）：按 `cacheKey = "$configId#$normalizedIndex"` 缓存于 `customServiceInstances`。
- `createServiceFromConfig`（`299-355`）：`getValidModelIndex` 归一化 → `config.copy(modelName = 选中模型)` → `AIServiceFactory.createService` → 按 `config.requestLimitPerMinute` / `maxConcurrentRequests` 包 `RateLimitedAIService`。
- `refreshServiceForFunction`（`223-239`）：清 `serviceInstances[functionType]`，且仅当 `functionType == CHAT` 时清空 `customServiceInstances`。

意图修正：

- 在"功能 → 落点"之间插入一次显式选点：`池 --选路--> 单条 (configId, modelIndex) --> 既有 createServiceFromConfig`。
- 选路只决定这一次用哪个候选。**不改工厂、不改缓存键规则、不做失败改投**。

新实现：

选路核（新文件 `com.ai.assistance.operit.api.chat.enhance.RouteSelector`）：

```kotlin
object RouteSelector {
    private val cursors = ConcurrentHashMap<FunctionType, AtomicInteger>()

    fun select(functionType: FunctionType, pool: FunctionConfigPool): FunctionRouteCandidate? {
        val enabled = pool.candidates.filter { it.enabled }
        if (enabled.isEmpty()) return null
        return when (pool.strategy) {
            RouteStrategy.FIXED -> enabled.first()
            RouteStrategy.ROUND_ROBIN -> {
                val c = cursors.getOrPut(functionType) { AtomicInteger(0) }
                enabled[Math.floorMod(c.getAndIncrement(), enabled.size)]
            }
            RouteStrategy.WEIGHTED -> {
                val c = cursors.getOrPut(functionType) { AtomicInteger(0) }
                weightedPick(enabled, Math.floorMod(c.getAndIncrement(), enabled.sumOf { it.weight.coerceAtLeast(1) }))
            }
        }
    }
}
```

- 空池（全部 `enabled = false` 或候选为空）返回 `null`。**这是配置错误，不允许自动改投别的候选**，调用方必须显式失败。
- 同文件另提供 `peek(functionType, pool)`：与 `select` 同源判定，但只读、不推进游标，仅供界面展示"下一个候选"。
- 游标按 `FunctionType` 进程内持有（`ConcurrentHashMap<FunctionType, AtomicInteger>`），进程重启归零。

接入点（`MultiServiceManager.getOrCreateServiceForFunctionLocked` 改写）：

```kotlin
val pool = functionalConfigManager.getRoutePool(functionType)
val candidate = RouteSelector.select(functionType, pool)
    ?: error("功能 $functionType 没有可用候选：请显式启用至少一个候选")
val managedService = getOrCreateServiceForConfigLocked(candidate.configId, candidate.modelIndex)
serviceInstances[functionType] = managedService
if (functionType == FunctionType.CHAT) defaultService = managedService
```

关键点：

- 服务缓存统一走 `customServiceInstances` 的 `"$configId#$index"` 键，因此每个候选各自持有服务实例；`ROUND_ROBIN` 才能真正在多个实例间轮转。`serviceInstances[functionType]` 退化为"最近一次解析结果"指针，供 `getServiceForFunction` 与 `defaultService` 兼容使用。
- CHAT 的 `defaultService` 仍指向最近解析到的那个候选实例，语义与旧实现一致（旧实现里 CHAT 只有一个点）。
- 取消/重置 token 已遍历 `customServiceInstances`，天然覆盖所有候选实例，无需改动。
- 限流与并发：仍由 `createServiceFromConfig` 内部对 `config.id` 用 `RateLimiterRegistry` / `RequestConcurrencyRegistry`。多个候选若是不同 configId 就天然分摊到各自的限流器；同 configId 不同 modelIndex 共享同一限流器（符合预期）。**不新增限流维度。**
- 刷新：`refreshServiceForFunction` 已改写。除清 `serviceInstances[functionType]` 外，解析该功能池全部候选，按 `"$configId#$index"`（`modelIndex.coerceAtLeast(0)` 归一化）逐个从 `customServiceInstances` 移除并 `retireManagedServiceLocked`；同时清空任何仍指向被收回实例的功能级指针（避免复用已退休服务），并在 `defaultService` 落在被收回集合时置空。旧实现"仅 CHAT 清 `customServiceInstances`"的粗粒度分支被移除。

并发：

- 选点与缓存读写都在 `serviceMutex.withLock` 内执行，保证同一功能同一时刻的解析顺序确定。
- 游标自增用 `AtomicInteger`，跨协程安全。

作用域：

- 新文件 `RouteSelector.kt`。
- `MultiServiceManager.kt`：`getOrCreateServiceForFunctionLocked`、`refreshServiceForFunction`。
- `functionalConfigManager.getRoutePool` 依赖自 02。

验证：

- `FIXED`：等价旧单点行为，连续多次 `getServiceForFunction` 命中同一服务实例。
- `ROUND_ROBIN`：N 个候选交替命中，且每个候选的服务实例只创建一次（`customServiceInstances` 不重复建）。
- 候选全部停用：抛错，不静默改投。
- 单候选失败：按现状抛错，不切换候选（对照 AGENTS.md 第 17 行）。

> 状态：已完成。代码 = `RouteSelector.kt`（新建）+ `MultiServiceManager.kt`（`getOrCreateServiceForFunctionLocked` / `refreshServiceForFunction`）；静态核对通过，按仓库执行准则未运行构建与测试。