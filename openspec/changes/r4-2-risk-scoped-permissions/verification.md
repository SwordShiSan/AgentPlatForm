# Required acceptance
- 工程：Maven verify、DDD/Enforcer/持久化边界、Mapper/XML；Vue typecheck/test/build；契约生成/非法fixture、links、strict。
- 迁移：全新安装、V15→V16、旧程序/任务默认RISK_BASED空绑定，V1–V15不变。
- PG/MQ：提交失败全回滚、并发幂等、资源绑定不变、权限修订竞争、重复grant/撤销、事件与投影一致；原outbox/MQ唯一路径保持。
- 策略：ACL/Plan/强制DENY/ASK优先，FULL不越权；目录NFC/段边界、到期、终态、policyVersion变化、未开放能力。
- 执行：双用户/管理员隔离；禁用/撤权、旧fence、目标改变、check与start间撤销及DB故障零未授权副作用；实际SDK DEFAULT与空rules。
- UI：真实API选择工作区、模式/目录授权/撤销、刷新/双标签/响应丢失、375/768/1440、200%缩放、键盘与浅暗主题。
- 回归：R4.1 Plan/QUESTION/文本续接、取消/凭据/诊断/SSE/每日默认1M与管理员不限额计量。

# Evidence and boundaries
各项记录命令、退出码、断言和文件SHA256。FAIL/NOT_RUN不改为PASS。真实供应商0，无云端/沙箱物理项目重跑；目录元数据夹具仅证明控制面。全部通过后保护PR晋级与归档，最终R4.2 VERIFIED/R4 2/4未RELEASED，云端R3不变。

# Current acceptance (2026-10-27)
全部本期功能门禁PASS，待保护PR晋级。精确业务源码9d3e91843c722f78240bc5bbe1c8520b76c248ec，后续浏览器/运维修订未改变业务源码和镜像。完整Maven verify通过，包含24项真实PG/MQ；SDK实验28项、Vue44项、真实API浏览器五场景分组通过。浏览器覆盖Plan兼容、工作区绑定、授权撤销、响应丢失仅一条授权、双标签412及管理员隔离。契约、类型、链接、strict和持久化边界通过。

索引见evidence/r42/acceptance-index.json和browser-cases.json，绑定源码、镜像、迁移及收据哈希；早期失败保留。受限栈2080MiB已停止，卷和镜像保留。真实供应商0；目录元数据不冒充物理隔离，未重跑XFS/gVisor或云端观察，不宣称最低物理主机或双并发容量。
