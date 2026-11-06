## Why

用户需要可复用的 Skill 和受控 MCP 能力，当前平台尚无版本目录、绑定及连接器。

## What Changes

- 导入并发布不可变 Skill 版本，个人及工作区绑定、只读物化。
- 受控 MCP 探测、版本确认、密钥管理及受保护调用验证。
- 生产聊天自动装配及按需调用留给 R6.4。

## Capabilities

### New Capabilities
- `skills-mcp`: 版本化能力管理、绑定及受控连接器。

### Modified Capabilities

## Impact

V23、公开契约、MyBatis、RabbitMQ、私有 S3、runner 签名与只读挂载、Vue；本地验收，云端继续 R5。
