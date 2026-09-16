# 现状与差距

旧实现（P0 动工前的实测基线，非推测）：

- 选路侧已完整：`FunctionConfigPool(candidates, strategy)`（`FunctionalConfigManager.kt:26-63`）、三策略 `RouteSelector`（`FIXED` / `ROUND_ROBIN` / `WEIGHTED`）、空池显式失败（`?: error(...)`），接入点 `MultiServiceManager.kt:140-145`。
- 配置侧已有宿主：工具层 `get_function_route_pool` / `set_function_route_pool`（`ToolRegistration.kt:681-699`）、JS 包装（`JsTools.kt:1042-1051`）、双语提示词（`SystemToolPromptsInternal.kt:2482-2502`）、i18n 9 键 × 8 locale。
- 数据库停在 v20：`chats` / `messages` / `message_variants` 三表，无任何调用链表。
- 全仓库检索 `AiCallTrace` / `CallSpan` / `traceId` / `spanId`：零命中。

差距（实测，逐条 grep 得出的零代码项）：

| 需求 | 动工前状态 | P0 处置 |
| --- | --- | --- |
| 主模型池多服务商 | 已有 | 沿用，补决策明细 |
| 旧配置兼容迁移 | 已有 | 沿用 |
| 调用链 trace/span 持久化 | 无 | 本阶段做 |
| 主模型实际选择可见 | 无（结论只在内存） | 本阶段落 `selected*` 字段 |
| 选路原因可解释 | 无（返回值只有候选） | 本阶段 `RouteDecision.reason` |
| 子模型 / 弱模型 / 降级链 | 无 | 留给 P1，仅预留 `SUB` / `WEAK` / `DEGRADED` 取值 |
| 敏感信息脱敏 | 无 | 本阶段做 |
| 查询接口 / 信息界面 | 无 | 留给 P2 |

可复用的既有资产（避免另起一套）：

- `AppLogger`：统一日志出口，观测失败走 `w` 级别，不抛。
- 既有迁移写法：`AppDatabase` 内 `object : Migration(x, y)` + `addMigrations(...)` 列表，本次沿用同一形态。
- 既有实体风格：数据类 + `@Entity(tableName = ..., indices = [...])` + `String` 存枚举 `name`（对照 `ChatEntity` / `MessageEntity`）。
- `FunctionType`：功能维度的稳定枚举，trace 的 `entryFunctionType` 与 span 的 `functionType` 直接取其 `name`，不新造枚举。

结论：P0 不需要改选路语义，只需要在既有的"选路 → 租约"路径上挂一层旁路观测，并把结果落进新表。
