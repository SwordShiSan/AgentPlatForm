## Why

R6.2 已交付用户能力目录和绑定管理，但还没有可核验的知识索引、用户审核的跨任务记忆或大历史安全压缩。R5 已提供私有资产、隔离提取和持久执行基础；R6.3 在此之上补齐可恢复的知识/记忆事实，并验证长上下文任务不会改写 canonical 执行账本。

## What Changes

- 新增 V24 知识库、版本化索引构建/分块、记忆生命周期和压缩投影账本；V1–V23及 runtime baseline 1 不变。
- 复用隔离 TEXT_EXTRACT、私有 SeaweedFS 资产、RabbitMQ outbox/claim/ACK、MyBatis/Spring短事务和 AgentScope 唯一 Harness。
- 引入本地离线 multilingual-e5-small INT8 推理，按准确源码提交及模型/tokenizer SHA256 锁定；PostgreSQL pgvector 精确向量检索与全文/中文词法检索融合。
- 提供知识库和检索 API/页面、个人记忆候选审核/管理、只读任务上下文摘要。
- 在 ChatExecution.beforeModel 接入 typed Memory/Retrieval/Context ports；仅保护的 R6.3 bundle启用。上下文缩减使用唯一平台确定性抽取投影，不新增摘要模型请求；canonical history 与任务账本保持不变。
- 本地验收、供应商调用0、云端继续R5；R6.4保留普通聊天能力自动装配、全来源面板及完整派生清理。

## Capabilities

### New Capabilities
- knowledge-index: 固定来源版本、离线构建可重试索引、ACL先行的混合检索及来源定位。
- personal-memory: 用户显式创建并审核记忆，支持隔离、修订、撤销与数据库时钟过期。
- context-compaction: 带来源/版本/水位的模型上下文装配和安全点压缩，不改变 canonical history。

### Modified Capabilities
无。现有文件资产、聊天和任务公开语义保持兼容；本期通过新增能力和受保护 bundle 接入，不扩展普通聊天自动装配。

## Impact

- Java platform-app/domain/infrastructure/api/trigger 六模块，Flyway V24，PostgreSQL 17/pgvector 0.8.1，RabbitMQ、SeaweedFS、隔离 renderer、共享 heavy-resource lease。
- ONNX Runtime Java 1.31.1、DJL Tokenizers 0.38.0；使用既有锁文件/Enforcer规则固定完整依赖及模型输入。
- OpenAPI、事件 payload、Vue知识和记忆页面、R6.3独立本地验收入口与证据索引。
- R6.4之后的 Skill/MCP自动装配、R6发布和云端部署不在本change内。
