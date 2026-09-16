# P2：查询入口与信息界面（只读出口）[DONE]

P0 只把调用链写进 `ai_call_trace` / `ai_call_span`，P1 补齐了会话归属、保留调度与租约终态回填。但数据躺在表里，**没有任何只读出口**：模型/用户既看不到"最近发生了什么调用"，也看不到单条 trace 的 span 树。P2 就是把这层出口补上。

范围收口：本期只做**读**。写入仍走 P0/P1 的采集与回填链路，查询侧不触发任何新的观测行为 —— 这是本期的红线。

## 旧实现

- 数据齐全（trace / span / token / error / degraded），零查询接口。
- 想核对一次选路，只能反编译看 DB 或加临时日志。信息界面缺失，多项验收（主模型实际选择可见、子模型调用可见、降级链路可见）拿不到可视化证据。

## 新实现

### 1. 数据层（改 1 文件）

| 文件 | 改动 |
| --- | --- |
| `data/dao/AiCallTraceDao.kt` | 新增 `suspend fun queryTraces(chatId: String?, status: String?, limit: Int): List<AiCallTraceEntity>`；`null` 条件走"不过滤"分支，统一在 SQL 侧处理，不在 Kotlin 侧 `filter` 二次遍历 |

### 2. 查询层（新 1 文件）

| 文件 | 内容 |
| --- | --- |
| `api/chat/enhance/trace/AiCallTraceQuery.kt` | `AiCallTraceFilter(chatId?, status?)` + `AiCallTraceDetail(trace, spanTree, totalSpanCount)`；`AiCallTraceQuery` 提供 `recentTraces(filter, limit)` / `traceDetail(traceId)` |

契约与归一化（`AiCallTraceFilter.Companion`）：

- `ALLOWED_STATUSES = { RUNNING, SUCCESS, FAILED, DEGRADED }`（与 `AiCallTraceStatus` 一致）。`isAllowedStatus(value)` 未知状态直接拒 —— **不把拼错的状态当成"查不到"**。
- `normalizeChatId` / `normalizeStatus`：`trim` 后空白转 `null`（= 不过滤），status 再 `uppercase()`。
- 界限：`DEFAULT_TRACE_LIMIT = 20`、`MAX_TRACE_LIMIT = 200`、`MAX_SPANS_PER_TRACE = 200`；`normalizeLimit(requested)` = `(requested ?: 20).coerceIn(1, 200)`。
- span 截断策略：超过 `MAX_SPANS_PER_TRACE` 只截断**展示树**，`totalSpanCount` 保留落库总数 —— 截断不丢真相。

### 3. 文本渲染（新 1 文件）

| 文件 | 内容 |
| --- | --- |
| `api/chat/enhance/trace/AiCallTraceTextFormat.kt` | `object`，`formatTraceList(...)` / `formatTraceDetail(...)`；内部 `appendNode`（缩进树）/ `formatTraceLine` / `statusMark`；出口 `masked(text)` |

- `masked` 复用 `SensitiveMasking.mask`：`Bearer xxx` / `api_key=xxx` / `sk-xxx` 一律脱敏 —— **出口边界统一脱敏，采集点不散着做**（沿用 P0 红线）。
- error 超长截到 200 字符缀 `…`；空/空白 error 渲染为 `null` 不出场（不占行）。
- `defaultTimeFormatter()` 可注入 `clock`，测试里给固定时间即可断言。

### 4. 工具出口（改 2 文件）

| 文件 | 改动 |
| --- | --- |
| `core/tools/defaultTool/standard/StandardAiCallTraceToolExecutor.kt` | 暴露 `TOOL_QUERY = "query_call_traces"` / `TOOL_DETAIL = "get_call_trace_detail"`；主构造改为**显式注入** `AiCallTraceQuery`（保留带 `Context` 的生产构造器），以支持单测 |
| `core/tools/defaultTool/ToolGetter.kt` | 新增 `getAiCallTraceToolExecutor(context)` |
| `core/tools/ToolRegistration.kt` | 注册两个工具，`descriptionGenerator` 读 `toolreg_*` 资源 |

参数契约（与 executor 严格对齐，键名入 Js 时用下划线）：

- `query_call_traces`：`limit` / `chat_id` / `status`
- `get_call_trace_detail`：`trace_id`

**任何非法输入显式 `error`，不静默返回空**：未知 `status` 报错；`chat_id`/`trace_id` 空白报错；小写 `status` 归一化后接受。

### 5. 系统集成（改 1 文件）

| 文件 | 改动 |
| --- | --- |
| `core/config/SystemToolPrompts.kt` | 新增 `callTraceTools` / `callTraceToolsCn` 两个分类；并入 `getAIAllCategoriesEn` / `getAIAllCategoriesCn` / `getManageableToolPrompts` **三处**列表 |

### 6. Js 命名空间（改 1 文件）

| 文件 | 改动 |
| --- | --- |
| `core/tools/javascript/JsTools.kt` | 在 `Memory` 块之后追加 `AiCallTraces` 命名空间：`query(limit, chatId, status)` + `detail(traceId)`；沿用 `Memory` 的双态入参样式（首参 object 走直传、否则走展开）；`toolCall` 无白名单，属全局桥接 |

### 7. i18n（改 8 locale）

`toolreg_query_call_traces_desc`（无参）/ `toolreg_query_call_traces_desc_status`（`%1$s`）/ `toolreg_get_call_trace_detail_desc`（`%1$s`），同步落 `values`、`values-en`、`values-es`、`values-id`、`values-ko`、`values-ms`、`values-pt-rBR`、`values-ro`，锚点 `toolreg_get_memory_by_title_desc` 后一行。`values-night` 是夜间资源、非 locale，跳过。

## 测试

新建 `FakeAiCallTraceDao`（JUnit 直接跑，不依赖 Room 测试环境；记录 `lastQueryChatId/Status/Limit` 供断言过滤透传）与 4 个测试类，共 **33 例**：

| 文件 | 例数 | 覆盖 |
| --- | --- | --- |
| `AiCallTraceFilterTest` | 5 | status 白名单、空/空白归一化、uppercase、非法状态拒绝 |
| `AiCallTraceQueryTest` | 8 | limit 钳制（缺省/越界）、filter 透传、traceId 空/缺失/命中、span 溢出截断但 `totalSpanCount` 保留 |
| `AiCallTraceTextFormatTest` | 10 | 脱敏、超长 error 截断、树与层级缩进、空树标记、时间格式化 |
| `StandardAiCallTraceToolExecutorTest` | 10 | 参数非法报错、`status` 拼错报错但小写接受、已知 trace 详情回显、未知 tool 拒掉 |

不用 mockk / Robolectric；`runTest` 驱动协程；`clock` 注入让时间渲染稳定可断言。

## 红线

- **只读出口不产生新的观测行为**：查询链路不写 trace / span，不碰 `chatId`。
- `chatId` 仅观测：选路 / 缓存 / 工厂 / 限流本期不碰（继承 P0/P1）。
- 脱敏只在出口做一次，采集点不动。
- 非法输入显式 `error`，绝不静默成空结果。

## 未做 / 移交

- **UI 信息页（P2.2，未做）**：本期只落工具出口 + Js 桥接，独立的信息界面页留待 P2.2，本轮不扩张 scope。
- **`ToolTesterScreen` 未补条目**：经核实该屏为**人工精选子集**（含 `query_memory`，但不含 `get_memory_by_title` / `create_memory`），并非全集平铺，故不塞 ai-call-trace 条目，避免为 2 条工具摊平 8 locale × N 条字符串的成本。若后续要补，参考 `query_memory` 行模式新增 2–4 条并配套 8 locale 字符串。

## 完成口径

代码已落地：数据层 / 查询层 / 文本渲染 / 工具出口 / 系统集成 / Js / i18n 全链路闭合，单元测试 33 例随代码落盘（已点数核对：5 + 8 + 10 + 10）。按仓库执行准则，未运行构建与测试。
