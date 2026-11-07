## Context

沿用 Java21、AgentScope 2.0.4 唯一 Harness、MyBatis、Spring 事务及 AMQP。R6.1 四项配置不改变。

## Goals / Non-Goals

完成真实版本管理、绑定、MCP 探测、受保护任务调用及材料挂载；生产聊天装配留 R6.4，不开放 SDK 自动扫描或 stdio。

## Decisions

- ZIP 先有界落盘，Commons Compress ZipFile 检查中央目录、每文件路径与真实字节并计算 SHA256；SDK MarkdownSkillParser 校验 SKILL.md。资源和包使用现有 S3 asset 和额度。
- 私有空间绑定固定版本，工作区缺省继承、显式空集合清空；撤销即时阻断新访问，旧事实保留。
- MCP 通过 SDK Streamable HTTP 和独立 Squid 7 受控出口；TLS 校验保留，出口按实际目标拒绝非公共地址，不可直连降级。内部仅运维预置别名。
- 外部连接地址本阶段不接收查询串：无法从任意参数名称可靠区分普通值和密钥，认证使用独立加密凭据及受控请求头；依赖查询串的服务需要后续扩展可审计的参数策略。
- 字面地址预检参考 [IANA IPv4 特殊地址登记](https://www.iana.org/assignments/iana-ipv4-special-registry) 和 [IPv6 特殊地址登记](https://www.iana.org/assignments/iana-ipv6-special-registry)；为避免转换前缀掩盖目标，保守拒绝部分特殊用途公网地址，实际 DNS 结果仍必须经出口逐次检查。
- NONE／BEARER／API_KEY 凭据加密入库并绑定修订；探测只发现不调用，schema 版本需显式启用。
- SDK 探测保留连接及调用超时；整个本地探测另设120秒看门狗。期限到达只能将原尝试标为结果不明并停止续租，不能认为远端或底层传输已经停止；消费者在真实调用返回前继续占用，迟到结果不能发布，也不能自动重试。
- 原操作通过 GuardedTool、权限、审批及 ledger；UNKNOWN 不重放。runner 新签名策略只挂明确版本的只读材料，旧策略不变。
- 材料 manifest 使用 `capability-materialization-v1` 长度前缀规范编码，绑定空间、真实任务、固定版本、压缩包与文件哈希及服务端派生的挂载目标；文件路径按 NFC 归一化，组件至多255个 UTF-8 字节，完整路径至多4096字节。此哈希本身不等于授权或挂载证明。
- HTTP、MQ、S3、Docker 均不在数据库事务内等待。运维角色和网络隔离采用最小权限。

## Risks / Trade-offs

网络与只读挂载须先做真实可行性实验；不满足安全边界时停止该功能，不做静默降级。

## Migration Plan

V23 仅加法，V1–V22 和 schema1 不变；旧任务及旧 bundle 不自动获得能力。
