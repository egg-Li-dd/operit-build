# 边界、兼容与验证 [DONE]

旧实现：

- 单一 JSON 键 `function_config_mapping`，两种历史形态：`{func: "configId"}`、`{func: {configId, modelIndex}}`。
- 无策略概念，无候选项，无游标。
- 刷新：`refreshServiceForFunction` 仅对 CHAT 清 `customServiceInstances`。

意图修正：

- 引入新键 `function_route_pool`，旧键仅作一次性迁移来源。
- 明确列出"不做"的边界，防止实现阶段被顺手塞进自动降级。

新实现／边界：

不做（红线，对照 AGENTS.md 第 17 行）：

- 不做失败改投：某候选请求失败不回退到其它候选。
- 不做健康探测 / 熔断 / 自动剔除候选。
- 不做静默兜底：空池、非法候选一律显式失败。
- 不改 `AIServiceFactory` 分派与 `"$configId#$index"` 缓存键规则。
- 不改角色卡固定模型的单点语义。
- 不新增限流维度，沿用 `RateLimiterRegistry` / `RequestConcurrencyRegistry` 的 `config.id` 键。

兼容 / 迁移：

- 读：新键 → 旧对象格式 → 旧字符串格式 → 默认。只有新键缺失才触达旧键。
- 迁移等价性：旧形态生成的池 `candidates = [旧点]`、`strategy = FIXED`，与旧行为逐功能等价。
- 写：只写新键。旧键保留在 DataStore 不主动删除，作为回滚锚点；发布一轮稳定后再单独清理。
- 降级版回滚：若需回退，旧键仍在即可直接回退代码，无需数据修复。

验证矩阵：

| 场景 | 输入 | 期望 |
| --- | --- | --- |
| 全新安装 | 无任何键 | 每功能单候选默认池，FIXED |
| 旧字符串格式 | `{ "CHAT": "cfg" }` | CHAT 候选 = [(cfg, 0)] |
| 旧对象格式 | `{ "CHAT": {configId, modelIndex:1} }` | CHAT 候选 = [(cfg, 1)] |
| 新池格式 | 多候选 + 策略 | 原样读回 |
| 单候选池 | strategy 任意 | 行为等价旧版 |
| 空启用集 | 全 enabled=false | 抛错，不改投 |
| 轮询 | 3 候选 | 依次命中，实例各建一次 |
| 越界 modelIndex | modelIndex 超范围 | `getValidModelIndex` 归一化 + 警告日志（沿用旧行为） |
| 刷新 | 池变更 | `refreshServiceForFunction` 清该功能全部候选实例 |

验证方式：

- 静态核对签名与消费点（对照 01 清单）。
- 单元测试：`LegacyRouteMappingMigrationTest`（旧对象 / 旧字符串 / 空键迁移）、`RouteSelectorTest`（三策略落点、空池与全禁用显式失败、peek 不推进游标）。
- 按仓库执行准则，未运行构建或测试命令；测试已随代码落盘。

i18n：

- 新增 9 个字符串（候选、策略名、说明提示、校验错误）已同步全部 8 个 locale；后续调整仍须全 locale 对齐。

待确认（决策矩阵）：

| 议题 | 方案 A（草案） | 方案 B | 影响 |
| --- | --- | --- | --- |
| 默认策略 | `FIXED`：与旧单点行为完全一致，升级零风险 | `ROUND_ROBIN`：开箱即分摊，但行为相对旧版可见变化 | 影响 02 默认值、04 设置页默认项 |
| 游标持久化 | 进程内：实现简单，重启归零 | DataStore 持久化：重启续轮 | 影响 03 游标存放位置与 02 键结构 |

推荐：

- 默认策略取 `FIXED`（迁移安全）。需要分摊的用户在设置页显式切 `ROUND_ROBIN`。
- 游标先进程内。跨进程如确有必要，另立一期改造，避免本方案膨胀。

> 状态：已完成。文档与迁移/选路单元测试已落盘（`LegacyRouteMappingMigrationTest`、`RouteSelectorTest`）；按仓库执行准则未运行构建与测试。