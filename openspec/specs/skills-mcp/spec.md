# skills-mcp Specification

## Purpose
TBD - created by archiving change r6-2-skills-mcp. Update Purpose after archive.
## Requirements
### Requirement: Skill versions are immutable and space-scoped
系统 SHALL 有界校验 Skill ZIP 并为通过发布的内容生成不可变版本，文件及哈希绑定空间。

#### Scenario: Valid package is published
- **WHEN** 授权用户导入有效包并确认发布
- **THEN** 系统保存有界 manifest 和固定版本，允许绑定

#### Scenario: Invalid archive is rejected
- **WHEN** 包越界、超限、包含链接、重复或规范化冲突路径
- **THEN** 系统拒绝发布且不暴露内容

#### Scenario: Revoked version is accessed
- **WHEN** 版本已撤销或用户丧失权限
- **THEN** 新读取和物化被拒绝，历史事实保留

### Requirement: Bindings preserve inheritance and isolation
系统 SHALL 以修订号管理个人和工作区固定版本绑定。

#### Scenario: Workspace inherits or clears
- **WHEN** 工作区未设置覆盖或显式设置空集合
- **THEN** 分别继承个人集合或得到空集合，版本冲突不覆盖并发修改

### Requirement: MCP discovery and calls are controlled
系统 SHALL 通过受控出口使用 AgentScope MCP 客户端并持久化不可变探测版本。

#### Scenario: Endpoint or credentials are invalid
- **WHEN** 地址不满足公共 HTTPS 或连接凭据已撤销
- **THEN** 探测被拒绝且不直接连接或暴露秘密

#### Scenario: Address includes query parameters
- **WHEN** 外部连接地址包含查询串
- **THEN** 本阶段拒绝该地址，认证由独立加密凭据及受控请求头提供

#### Scenario: Discovered schema changes
- **WHEN** 工具 schema 改变
- **THEN** 创建待确认新版本而不改变现有绑定

#### Scenario: Remote discovery response exceeds available memory or deadline
- **WHEN** 远端发现响应过大、子进程异常退出或期限内无法确认停止
- **THEN** 主工作进程不发布该版本，保留原探测待核对且不自动重放；密钥不进入命令参数、日志或证据

#### Scenario: Call outcome is unknown
- **WHEN** 受保护调用已发出但结果不明
- **THEN** 保存 UNKNOWN 并禁止自动重放

#### Scenario: A protected call uses the bounded SDK child
- **WHEN** 原任务工具操作已获准且 owner、connector version、credential revision 和 schema hash 均仍有效
- **THEN** 平台先持久化 RUNNING，再由受限子进程通过固定出口调用 SDK；结果提交前重新验证 fence，子进程本地停止不证明远端副作用停止

### Requirement: Materialization is explicit and read-only
系统 SHALL 仅将获准固定版本材料以只读方式提供给受保护真实测试任务。

#### Scenario: Mount or hash differs
- **WHEN** 实际挂载、材料哈希或授权不匹配冻结 manifest
- **THEN** 拒绝执行并保留相关收据

#### Scenario: Runner materialization is repeated or incomplete
- **WHEN** 相同 runner command 被重复提交，或挂载／停止／清理无法取得确定回执
- **THEN** 查询并返回原 command 事实，不重复解包或启动；不确定时保留 BLOCKED 和执行槽
