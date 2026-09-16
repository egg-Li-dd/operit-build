# 旧实现分析

旧实现：

- 数据层 `FunctionConfigMapping(configId, modelIndex)` 是单点映射，`modelIndex` 表示同一 `ModelConfigData.modelName` 内以逗号分隔的模型列表下标。
- `FunctionalConfigManager` 把 `Map<FunctionType, FunctionConfigMapping>` 以 JSON 存进 DataStore `functional_configs`，键为 `function_config_mapping`；同时保留只含 `configId` 的旧格式兼容读取。
- `FunctionalConfigManager.getConfigMappingForFunction(functionType)` 返回单条映射，缺失时回落到 `FunctionConfigMapping(DEFAULT_CONFIG_ID, 0)`。
- `MultiServiceManager` 通过映射取配置与服务：取 `configId` 拉取 `ModelConfigData`，用 `modelIndex` 经 `getValidModelIndex` 计算有效下标，复制出一份把 `modelName` 换成选中模型的配置，再交给 `AIServiceFactory.createService`。
- 服务实例按 `"$configId#$normalizedIndex"` 缓存于 `customServiceInstances`。
- `AIServiceFactory.createService` 按 `apiProviderTypeId` 分派具体提供商实现，多 Key 场景由 `MultiApiKeyProvider` 负责 Key 轮换。
- `EnhancedAIService.refreshServiceForFunction` 用于在映射变更后刷新缓存服务。

多处消费点读取同一映射，均默认"一个功能等于一个落点"：

- `AgentChatInputSection.kt`：读 `configMappingWithIndex[FunctionType.CHAT]`，角色卡锁定时用 `FunctionConfigMapping(cardConfigId, cardModelIndex)` 覆盖。
- `ClassicChatSettingsBar.kt`：同上，另用 `getValidModelIndex` 归一化后显示。
- `FunctionalConfigScreen.kt`：逐 `FunctionType` 取映射，`FunctionConfigCard` 里 `setConfigForFunction` + `refreshServiceForFunction`。
- `CharacterCardDialog.kt`：`fixedChatModelConfigId` / `fixedChatModelIndex` 单点，`getValidModelIndex` 归一化。
- `ContextSummarySettingsScreen.kt`、`MessageCoordinationDelegate.kt`：取 `FunctionType.CHAT` 映射读 `configId` 派生配置。
- `WebChatHttpBridge.kt`、`WebChatModels.kt`：角色卡锁定或当前选择派生 `currentConfigMapping`，回传归一化 `modelIndex`。
- 工具层：`StandardSoftwareSettingsModifyTools.kt`（读 / 写映射）、`ToolRegistration.kt`、`JsTools.kt`、`StandardFileSystemTools.kt`（列举映射）。

意图修正：

- 承认"一个功能可以有多个候选落点"这一事实，把单点映射升级为候选池。
- 不改动 `AIServiceFactory` 的提供商分派与服务缓存键规则，只在上游增加一次"选点"。

预期结果：

- 旧映射仍可读，语义等价于"只有一条候选的候选池"。
- 消费点在不改动调用方式的前提下继续可用。

验证：

- 静态核对 `FunctionConfigMapping`、`FunctionalConfigManager`、`MultiServiceManager` 的字段与方法签名。
- 全仓库检索确认当前不存在路由池实现。
- 按仓库执行准则不运行构建或测试命令。

> 状态：参考文档。本文件为旧实现分析，不含代码工作单元，不适用 `[DONE]` 标注（见 index.md 完成口径）。