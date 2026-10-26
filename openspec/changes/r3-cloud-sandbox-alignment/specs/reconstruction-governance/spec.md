> **DEFERRED / 保留方案，暂不实施（2026-10-26）**
> 用户因成本决定继续现行本地Docker＋gVisor/runsc、XFS、受信文件helper和SeaweedFS。本文件下方云方案仅保留研究，**非当前实施项、非R4前置**；不应用、不归档合并到主规格，实施任务保持未完成，云功能验收保持NOT_RUN。云专用2784MiB重分配与V15候选不生效，迁移号不预占；本地完整2880MiB基线继续计入sandbox/runtime/helper。

## ADDED Requirements

### Requirement: Cloud correction before R4
The platform SHALL keep R3 historical release facts intact and complete a separately planned and verified R3.C cloud-sandbox correction before R4 implementation. A planning strict result SHALL NOT mark cloud features or production cutover verified.
#### Scenario: Planning artifacts prepared
- **WHEN** the canonical plan and five OpenSpec artifact categories pass documentation checks
- **THEN** R3.C remains planning, productionR3 stays unchanged and SDK/functional/cutover tests remain NOT_RUN
#### Scenario: Later phase starts
- **WHEN** R4 is proposed
- **THEN** its plan uses verified cloud provider ports, synchronization and remote stop semantics, with no implicit resurrection of historical local sandbox assumptions
