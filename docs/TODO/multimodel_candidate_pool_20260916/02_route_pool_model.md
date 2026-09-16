# 候选池数据模型与持久化 [DONE]

旧实现：

- `FunctionConfigMapping(configId: String = DEFAULT_CONFIG_ID, modelIndex: Int = 0)` 是 `@Serializable` 单点映射，定义在 `FunctionalConfigManager.kt` 顶层。
- `functionConfigMappingWithIndexFlow: Flow<Map<FunctionType, FunctionConfigMapping>>` 从 DataStore `functional_configs` 的键 `function_config_mapping` 读取 JSON。
- 读取顺序：先按 `Map<String, FunctionConfigMapping>` 解；失败再按旧格式 `Map<String, String>`（值只是 configId）解，`modelIndex` 补 0；再失败回落全默认。
- 写入只走 `saveFunctionConfigMappingWithIndex(Map<FunctionType, FunctionConfigMapping>)`；`saveFunctionConfigMapping(Map<FunctionType, String>)` 是兼容包装（modelIndex 强制 0）。
- 对外单点访问器：`getConfigIdForFunction`、`getConfigMappingForFunction`、`setConfigForFunction(functionType, configId)`、`setConfigForFunction(functionType, configId, modelIndex)`、`resetAllFunctionConfigs`。

意图修正：

- 一个功能对应一个候选列表，而不是一个落点。列表元素仍是一个 `(configId, modelIndex)` 点，叠加启用位与权重。
- 选路策略是池的一等属性，随池一起持久化。
- 旧键 `function_config_mapping` 不删、不双写，只作为一次性迁移来源；`function_route_pool` 成为唯一可信源。

新实现：

新增可序列化结构（追加到 `FunctionalConfigManager.kt`）：

```kotlin
@Serializable
data class FunctionRouteCandidate(
    val configId: String = FunctionalConfigManager.DEFAULT_CONFIG_ID,
    val modelIndex: Int = 0,
    val enabled: Boolean = true,
    val weight: Int = 1
) {
    fun toMapping() = FunctionConfigMapping(configId, modelIndex)
}

enum class RouteStrategy { FIXED, ROUND_ROBIN, WEIGHTED }

@Serializable
data class FunctionConfigPool(
    val candidates: List<FunctionRouteCandidate> = listOf(FunctionRouteCandidate()),
    val strategy: RouteStrategy = RouteStrategy.FIXED
)
```

持久化：

- 新增 `val FUNCTION_ROUTE_POOL = stringPreferencesKey("function_route_pool")`。
- `functionRoutePoolFlow: Flow<Map<FunctionType, FunctionConfigPool>>`：读新键；新键缺失时执行一次性迁移，用旧键构造成单候选池（`strategy = FIXED`），随后仍不写回（写回发生在下一次任何 `save`）。
- 解析策略与旧键一致：结构体解析失败再尝试降级解析，但**不引入新的静默兜底**；解析异常一律回落到"单候选默认池"，并打 `AppLogger.w`。
- 写入只有一条路径 `saveRoutePool(Map<FunctionType, FunctionConfigPool>)`（另有单功能重载 `saveRoutePool(functionType, pool)`）。
- 复用性细节：`FunctionConfigPool.primaryCandidate()` / `primaryMapping()` 作为主候选派生点；`functionConfigMappingFlow`（值仅为 configId）与 `functionConfigMappingWithIndexFlow` 均由池派生主候选，既有消费点零改动。

兼容访问器（保持既有调用点不改）：

- `getConfigMappingForFunction(functionType)`：返回该功能池的**主候选**（`candidates.firstOrNull { it.enabled }?.toMapping()`，为空则 `candidates.firstOrNull()`，再为空给默认值）。语义从"唯一点"变为"主候选点"，历史调用点行为等价。
- `getConfigIdForFunction`：主候选的 configId。
- `setConfigForFunction(functionType, configId, modelIndex)`：改写主候选（`candidates[0]`），保留其余候选与策略不变；这是"单点 setter"的兼容语义。
- `resetAllFunctionConfigs()`：重建每功能单候选默认池（`FIXED`）。

新增访问器：

- `getRoutePool(functionType): FunctionConfigPool`
- `saveRoutePool(functionType, pool)`
- `getEffectiveCandidate(functionType)`：返回主候选，供显示层用（不参与轮询）。

作用域：

- `FunctionalConfigManager.kt`：新增结构、键、Flow、读写与迁移。
- 不改 `FunctionConfigMapping` 定义本身（复用为候选的映射视图）。

验证：

- 三种历史 JSON 都能读：纯 `{ "CHAT": "cfg" }`、`{ "CHAT": { "configId": "cfg", "modelIndex": 1 } }`、新池格式。
- 迁移后 `getConfigMappingForFunction` 与迁移前逐功能等价。
- 新键存在时不再读旧键。

> 状态：已完成。代码 = `FunctionalConfigManager.kt`；静态核对通过（解析/迁移路径、兼容访问器签名、消费点契约均一致）。按仓库执行准则未运行构建与测试。