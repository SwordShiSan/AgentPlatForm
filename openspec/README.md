# OpenSpec

R0.1工程与契约工具链已通过门禁并完成PR晋级，归档记录在 [R0.1验收记录](changes/archive/2026-10-13-r0-1-engineering-contract-toolchain/verification.md)。历史治理规格只定义文档与进度规则，不代表R0框架或业务实现。后继阶段必须独立Plan/OpenSpec，未完成时不归档。

每小阶段单独Plan/OpenSpec并strict通过后编码。工作规范见[工程规范](../../项目文档/06-AgentScope重构专项/00-总体方案/03-单仓与工程规范.md)。静态规格通过不等于框架、业务或生产部署通过。

R0.2框架契约与恢复探针已完成本地/CI和PR6/7晋级，[归档验收](changes/archive/2026-10-13-r0-2-agentscope-contract-probes/verification.md)记录方法签名及真实SDK/JVM/Linux证据；不是生产供应商/数据库/runner验收。

R0.3独立Plan已获批准，[存储/最小云资源change](changes/r0-3-storage-deploy-baseline/verification.md) strict通过后实施；开发验收改用云端Linux、单并发gVisor/XFS镜像，不以文档或编译替代真实探针/30min/旧栈恢复/两级PR。未完成不归档或标VERIFIED。
