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
- 固定 SDK 的 HTTP 响应聚合发生在平台 schema 大小检查之前。发现过程改为受信的独立 Java 子进程，固定堆与堆外预算、单进程、受控出口和总时限；凭据只经标准输入传递，不进入参数、环境、日志或证据。父进程对响应字节设上限并确认子进程实际退出，异常、超时及无法确认停止均保持原探测待核对，不自动重放。子进程预算纳入同一工作容器及总资源限额；真实限制、日志与故障须验证。业务工具调用仍须单独落实其停止及 UNKNOWN 边界。
- 原操作通过 GuardedTool、权限、审批及 ledger；UNKNOWN 不重放。runner 新签名策略只挂明确版本的只读材料，旧策略不变。
- 材料 manifest 使用 `capability-materialization-v1` 长度前缀规范编码，绑定空间、真实任务、固定版本、压缩包与文件哈希及服务端派生的挂载目标；文件路径按 NFC 归一化，组件至多255个 UTF-8 字节，完整路径至多4096字节。此哈希本身不等于授权或挂载证明。
- 材料下载后到 runner 接受挂载之间必须调用 `Prepared.verifyBeforeMount()`，重新验证同一 task lease、workspace 绑定及版本发布状态；校验通过仍不替代后续签名命令、持久回执及容器 inspect。
- HTTP、MQ、S3、Docker 均不在数据库事务内等待。运维角色和网络隔离采用最小权限。

### 受保护 MCP 调用的执行边界（R6.2 实施约束）

- 应用层只调用 `McpDiscoveryProcess.invoke(...)`；SDK `McpClientWrapper.callTool` 仅可在同一受限子进程内执行。不得提供应用进程内的直连调用适配器。
- 子进程输入绑定已冻结 connector version、tool name/schema hash 和受限参数，凭据仅经标准输入传递；进程继续通过固定出口和默认 TLS 校验。父进程限制参数 64KiB、子进程响应 64KiB（传输封装另设硬上限）并在退出与管道关闭确认后释放本地槽。
- 调用前重新核验 connector/version/credential 修订与原 operation 的审批和 owner/fence；在既有 operation ledger 进入 RUNNING 后才派发。调用返回后仍须复核 owner/fence 再提交结果。
- SDK 调用开始后任何超时、连接中断或进程异常都作为原 operation 的 UNKNOWN；不得重放。子进程被本地确认终止不等于远端副作用已停止。
- 连接器调用只由真实绑定的受保护测试任务使用；普通聊天的 connector/Skill 自动装配仍关闭，留 R6.4。

### Skill 材料的 runner 命令与只读挂载

- worker 暂存并校验材料后，调用带 RS256 身份、task lease、owner、attempt、generation/fence 和 manifest 哈希的版本化 runner 命令。命令 ID 与材料 manifest 一经受理即不可变；重复调用只查询原 operation。
- runner 重新查询真实任务租约、空间授权、工作区绑定、当前有效 binding 和不可变 Skill 版本，再从私有 S3 读取原始包、校验包哈希及逐文件 manifest，解压到 runner 独占的只读材料根目录。worker 临时目录不得假定与 Docker daemon 可见。
- 只读目录挂载是显式签名策略的一部分；容器 inspect 必须确认唯一可写 `/workspace` 加 manifest 列出的只读 `/capabilities/<capabilityId>/<versionId>`，其余 gVisor、无网络、非 root、只读根目录等硬限制不变。旧 bundle 不增加材料挂载。
- command/receipt 持久记录状态、owner、fence、manifest/result hash、精确容器/挂载身份与时间；数据库提交失败或挂载结果不明时保留 BLOCKED 和槽，不重复解包或启动。
- 物化验收结束后按精确 command 停止 sandbox、确认 exec/container/callback 静止，再清理本次私有目录并提交清理回执。未能证明停止或清理时保持待核对，不释放槽。

## Risks / Trade-offs

网络与只读挂载须先做真实可行性实验；不满足安全边界时停止该功能，不做静默降级。

## Migration Plan

V23 仅加法，V1–V22 和 schema1 不变；旧任务及旧 bundle 不自动获得能力。
