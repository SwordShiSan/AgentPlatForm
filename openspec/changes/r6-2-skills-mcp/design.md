## Context

沿用 Java21、AgentScope 2.0.4 唯一 Harness、MyBatis、Spring 事务及 AMQP。R6.1 四项配置不改变。

## Goals / Non-Goals

完成真实版本管理、绑定、MCP 探测、受保护任务调用及材料挂载；生产聊天装配留 R6.4，不开放 SDK 自动扫描或 stdio。

## Decisions

- ZIP 先有界落盘，Commons Compress ZipFile 检查中央目录、每文件路径与真实字节并计算 SHA256；SDK MarkdownSkillParser 校验 SKILL.md。资源和包使用现有 S3 asset 和额度。
- 私有空间绑定固定版本，工作区缺省继承、显式空集合清空；撤销即时阻断新访问，旧事实保留。
- MCP 通过 SDK Streamable HTTP 和独立 Squid 7 受控出口；TLS 校验保留，出口按实际目标拒绝非公共地址，不可直连降级。内部仅运维预置别名。
- NONE／BEARER／API_KEY 凭据加密入库并绑定修订；探测只发现不调用，schema 版本需显式启用。
- 原操作通过 GuardedTool、权限、审批及 ledger；UNKNOWN 不重放。runner 新签名策略只挂明确版本的只读材料，旧策略不变。
- HTTP、MQ、S3、Docker 均不在数据库事务内等待。运维角色和网络隔离采用最小权限。

## Risks / Trade-offs

网络与只读挂载须先做真实可行性实验；不满足安全边界时停止该功能，不做静默降级。

## Migration Plan

V23 仅加法，V1–V22 和 schema1 不变；旧任务及旧 bundle 不自动获得能力。
