# 脱敏边界与树形还原 [DONE]

旧实现：

- 无脱敏工具。异常信息一旦落库就是把 apiKey 原样写进 SQLite：`HttpStatusCodeException` 的响应体、被拼进日志的 URL query、Authorization 头都在其中。
- 无树形还原。span 会是平铺列表，父子关系无处表达。

意图修正：

- 脱敏只在**出口边界做一次**（`AiCallTraceSink` 实现与 `AiCallTraceRecorder` 的写入路径）。理由：采集点分散在多处，漏一个就漏一份隐私；收口到一处才可审计。
- 只处理"可能夹带凭据的自由文本"（`errorMessage`），不碰结构化字段：`configId` / `modelName` / `provider` 本身不含凭据，打码只会让界面失去可读性。
- 树形还原三条规则：排序稳定、父缺失不丢 span、环不递归爆栈。

新实现：

脱敏（`util/SensitiveMasking.kt`）：

| 模式 | 正则要点 | 效果 |
| --- | --- | --- |
| Bearer 头 | `(?i)\bBearer\s+[A-Za-z0-9._\-]{4,}` | `Bearer ***`（保留 scheme，看得出是鉴权头） |
| 具名凭据 | `(?i)\b(api_key\|apikey\|access_token\|accesstoken\|secret\|password\|passwd)\b\s*[:=]\s*[^\s"',&]+` | `api_key=***`（保留键名，URL query 同样命中） |
| 厂商 key | `\b(?:sk\|rk\|pk)-[A-Za-z0-9_\-]{4,}` | `sk-***`（保留前缀，看得出是哪家的 key 配错了） |

- 先 Bearer、再具名、最后前缀 key：顺序保证 `Bearer sk-xxx` 不会先被拆成半截。
- `null` 与空串原样返回，避免把"无内容"渲染成 `***` 这种假信息。
- 刻意保守：`{4,}` 的短凭据容忍度意味着 `sk-ab` 这类超短串不会被打码（真 key 远长于此），换来的是不误伤 `task=done` / `risk-averse` / `desk-lamp` 这类正常文本——正则过宽的代价是正常报错被吃掉，比漏打一个不存在的短 key 更糟。
- `AiCallTraceMasking.kt` 提供 `AiCallTraceEntity.maskedForStorage()` / `AiCallSpanEntity.maskedForStorage()` 两个视图扩展；`RoomAiCallTraceSink` 与 `AiCallTraceRecorder` 写前各过一遍（幂等，重复脱敏结果不变）。

树形还原（`api/chat/enhance/trace/AiCallSpanTree.kt`）：

```kotlin
data class AiCallSpanNode(val span: AiCallSpanEntity, val children: List<AiCallSpanNode>)

data class AiCallSpanTree(val roots: List<AiCallSpanNode>) {
    val spanCount: Int          // 树内节点总数
    val failedSpanCount: Int    // FAILED 节点数
    val maxDepth: Int
    fun flatten(): List<AiCallSpanNode>   // 深度优先展开，供列表渲染
}

object AiCallSpanTreeBuilder { fun build(spans: List<AiCallSpanEntity>): AiCallSpanTree }
```

三条规则：

1. 排序稳定：`startedAt` -> `attemptIndex` -> `spanId`。同一份数据渲染结果必须一致，否则界面每次重组都跳一次。
2. 父缺失（父被清理、跨 trace 脏数据、`parentSpanId` 指向不存在的 id）时该 span **升为 root，绝不丢弃**；自引用（`parentSpanId == spanId`）同样按 root 处理。
3. 环检测：parent 链成环时，成环的边一律不采用、成员升为 root。

规则 3 的修正是本轮动工中发现并修掉的真实缺陷：初版只在递归下行时用 `activePath` 剪断环，但纯环（`A.parent=B`、`B.parent=A`）不会产生任何 root —— `roots` 为空，整条链静默消失，`spanCount` 直接归零；而 `activePath` 版的兜底还会把被剪断的 span 重复计入，`spanCount` 虚高。现版本在**建边阶段**先沿 `parentSpanId` 链判定环（带 `cycleMembers` / `acyclic` 记忆化，判定是均摊 O(1)），成环成员不进 `childrenByParent`、直接进 `roots`：无重复、无丢失、无递归爆栈。代价是脏数据里"指向环"的 span 会少一层父子关系——比丢数据好。

验证：

- `SensitiveMaskingTest`（7 例）：Bearer / 具名凭据 / 厂商 key 三类脱敏且保留识别前缀；`null` 与空串原样；正常报错文本不被改写；`task=done` / `risk-averse` / `desk-lamp` / 裸 `Bearer` 无假阳性；异常栈里的 key 被抹掉但报错语义（`Incorrect API key`）保留。
- `AiCallSpanTreeTest`（9 例）：空输入；父缺失升 root；嵌套深度与 `flatten` 顺序；兄弟节点三级排序键（`startedAt` / `attemptIndex` / `spanId`）全部生效；自引用计一次；**两节点纯环两个 span 都在且不重复计数**；带入口的环（`A -> B <-> C`）三个都不丢；`failedSpanCount` 跨层统计；500 层深链不爆栈。

> 状态：已完成。代码 = `SensitiveMasking.kt`、`AiCallTraceMasking.kt`、`AiCallSpanTree.kt`（新建，其中 `AiCallSpanTree.kt` 本轮修掉纯环丢数据缺陷）；静态核对通过，按仓库执行准则未运行构建与测试。