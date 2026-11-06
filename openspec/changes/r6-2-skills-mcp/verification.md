## Acceptance

状态 NOT_RUN。绑定源码、迁移、依赖和镜像；真实供应商调用0。

## 阶段内记录

- 能力绑定纯领域测试 2 项 PASS。Skill ZIP 校验的定向测试 5 项 PASS，包含链接及文件数量边界；尚未接入导入和持久链路，不能视为完整包安全验收。
- 官方维护的 `ubuntu/squid:7.2-26.04_beta` 查询未找到；`7.2-26.04_edge` 的 linux/amd64 manifest 已固定并拉取，无网络容器中执行二进制报告 7.7，而 tag／OCI label 报告 7.2。实验身份见 `deploy/config/r62-squid-image.json`；受控出口功能、DNS／实际目标验证仍 NOT_RUN。

- 全新和 V22→V23，真实 PG／MQ／S3、并发、幂等、额度及依赖故障。
- ZIP 20MiB／展开100MiB／1000文件，路径、链接、归一化和资源缺失；双用户与管理员隔离。
- MCP 真实 TLS 夹具、出口实际地址检查、schema变化、密钥撤销、审批、未知结果不重放。
- 精确材料 manifest、只读挂载、撤权和旧策略兼容；本机缺 XFS／gVisor 时独立云端短窗口并恢复 R5。
- Maven／架构、前端类型／单测／build、契约、链接、strict 和真实浏览器；最终候选180秒资源观察。
- 总限额2887MiB、宿主余量768MiB、根盘余量5GiB；失败保留证据，不提前标 VERIFIED。
