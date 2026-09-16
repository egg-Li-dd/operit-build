---
fork_repository: https://github.com/AAswordman/Operit.git
---

# 多模型候选池与显式选路

当前每个 `FunctionType` 只能绑定一个模型落点：`FunctionConfigMapping(configId, modelIndex)` 是单点映射，`MultiServiceManager` 按功能缓存单个服务实例。用户已确认需求为"多模型候选"，即同一功能可配置多个候选落点。

本次把功能到模型的映射从单点扩展为候选池，并引入显式选路策略。选路只决定"这一次用哪个候选"，不包含任何自动切换、降级或兜底行为：被选中的候选请求失败时按现状抛错，不会改投其它候选。

作用域：

- `FunctionConfigMapping` 及相关持久化结构
- `FunctionalConfigManager` 的读写与迁移
- `MultiServiceManager` 的服务解析路径
- `FunctionalConfigScreen` 与相关工具入口

实现步骤：

- [01 旧实现分析](01_old_implementation.md)
- [02 候选池数据模型与持久化](02_route_pool_model.md)
- [03 选路核与管理器接入](03_routing_kernel.md)
- [04 界面与工具入口](04_ui_and_tools.md)
- [05 边界、兼容与验证](05_boundaries_and_verification.md)

## 待确认

- 各功能的默认选路策略。当前草案以 `FIXED`（固定取首个启用候选）为默认，以保持与现有单点行为一致；可选 `ROUND_ROBIN` 与 `WEIGHTED`。
- 是否需要跨进程持久化轮询游标。草案为进程内游标，进程重启后重置。

两项的取舍见 [05 边界、兼容与验证](05_boundaries_and_verification.md) 的决策矩阵。

## 完成口径

本方案代码已落地。按仓库 TODO 规范，`[DONE]` 在**完成对应代码、文档同步与验证后**追加到该文档一级标题末尾：02/03/04/05 已标注 `[DONE]`；01 为旧实现分析参考，不含代码工作单元，不单独标注。单元测试 `LegacyRouteMappingMigrationTest` / `RouteSelectorTest` 已随代码落盘。