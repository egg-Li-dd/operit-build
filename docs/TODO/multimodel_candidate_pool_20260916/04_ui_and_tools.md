# 界面与工具入口 [DONE]

旧实现：

- `FunctionalConfigScreen.kt`：逐 `FunctionType` 渲染 `FunctionConfigCard`；`onConfigSelected(configId, modelIndex)` → `setConfigForFunction` → `EnhancedAIService.refreshServiceForFunction`。卡片单点展示"配置名 + 选中模型名"，用 `getValidModelIndex` 归一化。
- `AgentChatInputSection.kt` / `ClassicChatSettingsBar.kt`：输入区选择器读 `configMappingWithIndex[FunctionType.CHAT]`，角色卡锁定时用卡片映射覆盖；回调 `onSelectModel(configId, modelIndex)`。
- `CharacterCardDialog.kt`：角色卡固定聊天模型是 `fixedChatModelConfigId` / `fixedChatModelIndex` 单点。
- `WebChatHttpBridge.kt` / `WebChatModels.kt`：Web 侧"当前选择"与角色卡锁定派生单条 `currentConfigMapping`。
- 工具：`StandardSoftwareSettingsModifyTools.kt` 提供功能↔模型映射读写；`ToolRegistration.kt` / `JsTools.kt` / `StandardFileSystemTools.kt` 列举映射。

意图修正：

- 设置页从"选一个点"升级为"维护候选池"：增删候选、启用/停用、选策略。
- 输入区、Web Chat、角色卡、工具**不改交互模型**：面向"主候选"，行为与旧版一致；池维护集中在设置页。
- 角色卡固定模型保持单点，不参与池（超出本次作用域）。

新实现：

`FunctionalConfigScreen.kt`：

- `FunctionConfigCard` 入参由 `currentModelIndex: Int` 改为 `pool: FunctionConfigPool`，回调改为 `onPoolChanged: (FunctionConfigPool) -> Unit`。
- 卡片内新增候选列表：每行一个 `FunctionRouteCandidate`（配置 + 模型 + 启用开关 + 删除），底部"添加候选"。
- 新增策略选择器（FIXED / ROUND_ROBIN / WEIGHTED）。轮询策略下展示只读"下一个候选"提示。
- `onPoolChanged` 落盘后调 `EnhancedAIService.refreshServiceForFunction(context, functionType)`，复用既有刷新入口。
- 校验：configId 必须在 `configSummaries` 中且模型名非空；至少保留一个启用候选；否则就地报错，**不静默修正**。
- 新增字符串资源须同步各 locale（见 05）。

输入区（`AgentChatInputSection.kt` / `ClassicChatSettingsBar.kt`）：

- 读主候选（`getConfigMappingForFunction` 语义），不再直接遍历 Flow 的原始条目。
- `onSelectModel(configId, modelIndex)` 落盘仅改写主候选，**不动其余候选与策略**。
- 可选增量：主候选名旁加"N 候选 / 策略"badge。

角色卡与 Web Chat：

- `CharacterCardDialog.kt`：不改。角色卡固定模型仍单点，且锁定优先级高于池。
- `WebChatHttpBridge.kt` / `WebChatModels.kt`：不改协议；"选择模型"写主候选，展示取主候选，语义不变。

工具层：

- `StandardSoftwareSettingsModifyTools.kt`：现有读写语义保持——读返回主候选，写改写主候选。
- 新增只读 `get_function_route_pool(functionType)` 与写 `set_function_route_pool(functionType, candidates, strategy)`，做与 UI 等价的校验。
- `ToolRegistration.kt` / `JsTools.kt` / `StandardFileSystemTools.kt`：列举结果仍报主候选，不改返回结构；如需报全池，新增字段而非改旧字段。

作用域：

- `FunctionalConfigScreen.kt`（主改造）、`FunctionConfigCard` 调用面。
- 输入区两个文件（主候选读取与写入语义）。
- 工具层新增 2 个工具 + 字符串资源。

验证：

- 增删候选、切策略、重启后池结构完整。
- 单候选池在 UI 上等价旧版。
- 至少一个候选启用为硬约束；非法状态被拒。
- 旧工具读到的值仍等价于旧实现。

> 状态：已完成。代码 = `FunctionalConfigScreen.kt`（`FunctionConfigCard` 候选池化：候选增删启停 + 策略选择器 + `function_route_pool_hint` 说明文案）+ `StandardSoftwareSettingsModifyTools.kt`（`get_function_route_pool` / `set_function_route_pool`）+ `ToolRegistration.kt` / `JsTools.kt` 注册与 JS 包装 + 9 个字符串 × 8 locale；静态核对通过，按仓库执行准则未运行构建与测试。