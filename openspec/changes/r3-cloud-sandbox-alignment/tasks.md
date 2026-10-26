> **DEFERRED / 保留方案，暂不实施（2026-10-26）**
> 用户因成本决定继续现行本地Docker＋gVisor/runsc、XFS、受信文件helper和SeaweedFS。本文件下方云方案仅保留研究，**非当前实施项、非R4前置**；不应用、不归档合并到主规格，实施任务保持未完成，云功能验收保持NOT_RUN。云专用2784MiB重分配与V15候选不生效，迁移号不预占；本地完整2880MiB基线继续计入sandbox/runtime/helper。

## 0. Planning only in this turn
- [x] 0.1 Inspect current source/stage and official cloud documentation; record SDK source/version drift and limitations.
- [x] 0.2 Query existing instance using read-only list/getInfo with redacted output, without connect/wake/kill.
- [x] 0.3 Write canonical plan, architecture audit, ADR and targeted current/future documentation; preserve production and historical acceptance scope.
- [x] 0.4 Validate all delta specs strictly, link consistency and redaction; record planning checks without claiming functional acceptance.

## 1. P0 feasibility after plan confirmation
- [ ] 1.1 Lock unmodified official SDK commit/source archive/JAR/transitive dependencies/license; verify Java21 and Jackson2/3.
- [ ] 1.2 Verify actual template/runtime/QoS/region and isolated fixture creation/query/termination/one-instance binding.
- [ ] 1.3 Prove100MiB bounded transfer, zero-byte/truncated input/hash, bounded output and thread/connection cleanup without SDK source patches.
- [ ] 1.4 Prove remote stopping, pending-response reconciliation, create-time network policy and enforceable workspace/output quota; stop and report if necessary capabilities fail.

## 2. P1 persistent provider migration
- [ ] 2.1 Freeze V15 additive schema and ENVIRONMENT_CLOUD_V1, provider-neutral domain ports and typed DTOs; preserve old facts.
- [ ] 2.2 Implement managed Java adapter and Repository/Mapper/PO/XML transactions, reliable MQ handoff, original-command query and authorization/fence guards.
- [ ] 2.3 Verify fresh/V14 upgrade, rollback compatibility, duplicate/confirm/ACK loss and single-slot races with real PG/MQ.

## 3. P2/P3 full functionality
- [ ] 3.1 Implement input/output manifests, bounded transfer, baseVersion conflicts, exact per-file receipts and XFS/S3 settlement.
- [ ] 3.2 Verify100MiB public files, old versions/Range/UTF8, double users, quota and stopped/deleted environment independent access.
- [ ] 3.3 Implement prepare/sleep/wake/delete/idle60s with remote stopping/collection/cleanup and no blind UNKNOWN retry.
- [ ] 3.4 Update contracts/generated client and truthful UI; verify actual API/browser states and permission revocation.

## 4. P4 acceptance, cutover and closure
- [ ] 4.1 Run engineering and affected R1/R2/R3 functional/resource/recovery regression without real model calls; count all local temporary resources.
- [ ] 4.2 Freeze exact source/artifacts/evidence and complete protected step->phase->main promotion with Actions disabled.
- [ ] 4.3 Back up/drain current productionR3; switch same immutable candidate and verify all delivered functions; explicit rollback preserves remote facts and files.
- [ ] 4.4 Record new production identity, update fast-switch/Skill constraints and stop test resources; archive only after real verification, then plan R4 separately.
