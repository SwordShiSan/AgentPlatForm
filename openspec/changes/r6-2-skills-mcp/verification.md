## Acceptance

状态 NOT_RUN。绑定源码、迁移、依赖和镜像；真实供应商调用0。

## 阶段内记录

- 能力绑定纯领域测试 2 项 PASS。Skill ZIP 校验的定向测试 5 项 PASS，包含链接及文件数量边界；尚未接入导入和持久链路，不能视为完整包安全验收。
- 官方维护的 `ubuntu/squid:7.2-26.04_beta` 查询未找到；`7.2-26.04_edge` 的 linux/amd64 manifest 已固定并拉取，无网络容器中执行二进制报告 7.7，而 tag／OCI label 报告 7.2。实验身份见 `deploy/config/r62-squid-image.json`；受控出口功能、DNS／实际目标验证仍 NOT_RUN。
- `AgentScopeMcpCompatibilityTest` 在独立回环 Tomcat 使用官方 SDK Streamable HTTP 服务，发现工具时调用计数为0，显式调用后计数为1，PASS；该探针是明文回环，仅证明 SDK 兼容，不代表 TLS、受控出口或平台账本通过。测试停止时 Tomcat 对 Reactor 共享线程发出清理提示，退出码0；持续资源释放仍需后续受限环境观察。
- 出口夹具首次使用64MiB时容器启动即 OOMKilled、退出137；独立诊断记录保留于 `.cache/r62-egress/887aade38e3d`。拟在总预算内按实际需要调整限额，不能将此失败记为通过。
- 将容器限额提高至128MiB后仍在启动时 OOMKilled，记录见 `.cache/r62-egress/94795279a6dc`；进一步以显式受限缓存配置验证，原因尚待实证。
- 限制文件描述符至1024并设置8MiB缓存后，128MiB容器正常启动，日志见 `.cache/r62-egress/de2b86b1991e`；代理拒绝测试驱动最初读取 HTTP 状态而非 CONNECT 状态，尚需修正后验证，不能将该次记录标 PASS。
- 修正测试驱动后，隔离代理对回环地址返回 CONNECT 403，定向实验 PASS，证据见 `.cache/r62-egress/fded2d2923a0`。该结果不覆盖其他保留地址、域名重绑定、真实 TLS、SDK 或平台审批账本。

- 全新和 V22→V23，真实 PG／MQ／S3、并发、幂等、额度及依赖故障。
- ZIP 20MiB／展开100MiB／1000文件，路径、链接、归一化和资源缺失；双用户与管理员隔离。
- MCP 真实 TLS 夹具、出口实际地址检查、schema变化、密钥撤销、审批、未知结果不重放。
- 精确材料 manifest、只读挂载、撤权和旧策略兼容；本机缺 XFS／gVisor 时独立云端短窗口并恢复 R5。
- Maven／架构、前端类型／单测／build、契约、链接、strict 和真实浏览器；最终候选180秒资源观察。
- 总限额2887MiB、宿主余量768MiB、根盘余量5GiB；失败保留证据，不提前标 VERIFIED。
