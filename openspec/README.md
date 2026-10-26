# OpenSpec

保留变更：[r3-cloud-sandbox-alignment](changes/r3-cloud-sandbox-alignment/proposal.md)，其[设计](changes/r3-cloud-sandbox-alignment/design.md)、[任务](changes/r3-cloud-sandbox-alignment/tasks.md)、[验证记录](changes/r3-cloud-sandbox-alignment/verification.md)及全部delta均为 **DEFERRED／保留方案，非当前实施项、非R4前置**。用户因成本继续现行本地Docker/gVisor、XFS/helper和SeaweedFS；主规格仍描述已交付本地行为，不归档合并云delta。保留目录可能仍被CLI枚举，不表示可以实施；实施任务不勾选、功能验收保持NOT_RUN。R3 RELEASED保持，下一项单独准备R4.1 Plan/OpenSpec；本次仅文档纠正。

R0.1工程与契约工具链已通过门禁并完成PR晋级，归档记录在 [R0.1验收记录](changes/archive/2026-10-13-r0-1-engineering-contract-toolchain/verification.md)。历史治理规格只定义文档与进度规则，不代表R0框架或业务实现。后继阶段必须独立Plan/OpenSpec，未完成时不归档。

每小阶段单独Plan/OpenSpec并strict通过后编码。工作规范见[工程规范](../../项目文档/06-AgentScope重构专项/00-总体方案/03-单仓与工程规范.md)。静态规格通过不等于框架、业务或生产部署通过。

R0.2框架契约与恢复探针已完成本地/CI和PR6/7晋级，[归档验收](changes/archive/2026-10-13-r0-2-agentscope-contract-probes/verification.md)记录方法签名及真实SDK/JVM/Linux证据；不是生产供应商/数据库/runner验收。

R0.3独立Plan已获批准，[存储/最小云资源归档验收](changes/archive/2026-10-13-r0-3-storage-deploy-baseline/verification.md) 已完成strict、真实云端探针、30min、旧栈恢复及PR10/11晋级并VERIFIED/归档；开发验收采用云端Linux和单并发gVisor/XFS镜像，生产R0仍待R0.4。
