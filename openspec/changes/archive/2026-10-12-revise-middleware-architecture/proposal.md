## Why

现有设计以少组件为优先且缺少用户持久资产统一归属，需要按云端Agent平台的可靠性、隔离与扩展要求重新选择中间件并明确生命周期。本变更仅修订方案与验收门禁，不实现运行能力。

## What Changes

- 修订PG常规任务队列为RabbitMQ可靠投递、PG权威租约/outbox/inbox，保留唯一ReAct与平台执行所有权。
- 明确SeaweedFS对象、XFS工作区、Quartz时间投影及观测栈选型。
- 新增个人/组织数据归属、Skill物化、存储额度、删除/导出和Vue管理交互方案。
- 同步既有ADR、契约、模块、部署与阶段门禁；实现仍NOT_STARTED。

## Capabilities

### New Capabilities

### Modified Capabilities

- `reconstruction-governance`: 文档基线必须包含可靠消息与用户数据归属，并将未运行验证留在对应阶段门禁。

## Impact

方案正文只在现有06-AgentScope重构专项；新仓仅保存治理变更与导航。没有业务API、数据库、容器、旧项目或生产变更，不新增后置功能，不推送外部正文。
