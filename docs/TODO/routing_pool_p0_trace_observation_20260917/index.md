---
fork_repository: https://github.com/AAswordman/Operit.git
---

# 路由池调用链可观测地基（P0）

上一期 [多模型候选池与显式选路](../multimodel_candidate_pool_20260916/index.md) 把"功能 → 落点"从单点换成了候选池 + 显式策略。但选路结果只活在内存里：界面能看到"配置了什么"，看不到"这一次实际选了谁、为什么是它、中途换过没有"。

多项验收标准（主模型实际选择可见、子模型调用可见、降级链路可见、敏感信息脱敏）全都压在"先把调用链记下来"这件事上。P0 就是这层地基。

作用域：

- 数据层：`ai_call_trace` / `ai_call_span` 两张表 + Room `v20 -> v21` 迁移
- 采集层：`RouteSelector` 暴露决策明细；`MultiServiceManager` 在租约生命周期内登记 trace/span
- 出口层：`AiCallTraceRecorder`（单例）+ `AiCallTraceSink` 抽象 + Room 实现，落库前统一脱敏
- 展示基础：`AiCallSpanTreeBuilder` 把平铺 span 还原成树
- 测试：27 例

实现步骤：

- [01 现状与差距](01_current_state_and_gap.md)
- [02 数据模型与 Room 迁移](02_trace_data_model_and_migration.md) [DONE]
- [03 记录器与选路注入](03_recorder_and_injection.md) [DONE]
- [04 脱敏边界与树形还原](04_masking_and_span_tree.md) [DONE]
- [05 边界、验证与 P1 移交](05_boundaries_and_verification.md) [DONE]
- [06 P1：会话归属与调用链保留调度](06_p1_session_attribution_and_retention.md) [DONE]
- [07 P1：租约终态回填（token / status）](07_p1_degraded_token_backfill.md) [DONE]
- [08 P2：查询入口与信息界面](08_p2_query_entry_and_info_ui.md) [DONE]

## P0 不做（红线）

- 不做降级 / 弱模型兜底 —— 那是 P1。本阶段只预留 `WEAK` / `DEGRADED` 取值与 `degraded` 字段。
- 不做子模型池 —— sub 池尚不存在，`SUB` tier 只是预留位。
- 不做查询接口与信息界面 —— 那是 P2，依赖本阶段先落库。→ P2 已落只读出口（工具 + Js 桥接 + 文本渲染），见 [08](08_p2_query_entry_and_info_ui.md)；独立 UI 信息页留 P2.2。
- 不在采集点散着脱敏 —— 只在出口边界做一次（见 04）。
- 不猜调用成败 —— P0 的 span 语义是"选路决策 + 租约存活期"，成功/失败的真实语义留给 P1 由业务层回填。

## 待确认

- ~~`chatId` 目前恒为 `null`~~ → 已由 P1 透传，见 [06](06_p1_session_attribution_and_retention.md)。
- ~~trace 保留策略未定~~ → 已由 P1 落每日清理（默认保留 7 天），见 [06](06_p1_session_attribution_and_retention.md)。
- ~~`span.status` 恒 `SUCCESS`、token 恒 0~~ → 已由 P1 第二期按租约终态回填（含首次失败根因），见 [07](07_p1_degraded_token_backfill.md)。
- 降级链仍无触发方：`DEGRADED` / `WEAK` 依旧只是占位，本期只留写入位，见 [07](07_p1_degraded_token_backfill.md)。

## 完成口径

本方案代码已落地。按仓库 TODO 规范，`[DONE]` 在**完成对应代码、文档同步与验证后**追加：02/03/04/05 已标注；01 为现状分析参考，不含代码工作单元，不单独标注。单元测试 `RouteSelectorDecisionTest` / `AiCallSpanTreeTest` / `SensitiveMaskingTest` / `AiCallTraceSchemaGuardTest` 已随代码落盘（共 27 例）。P1 两期续做见 [06](06_p1_session_attribution_and_retention.md)（chatId 透传 + retention 调度，11 例）与 [07](07_p1_degraded_token_backfill.md)（租约终态回填，12 例）。P2 只读出口见 [08](08_p2_query_entry_and_info_ui.md)（数据层 / 查询层 / 文本渲染 / 工具出口 / 系统集成 / Js / i18n 全链路闭合，33 例）。按仓库执行准则，未运行构建与测试。