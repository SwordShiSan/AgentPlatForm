> **DEFERRED / 保留方案，暂不实施（2026-10-26）**
> 用户因成本决定继续现行本地Docker＋gVisor/runsc、XFS、受信文件helper和SeaweedFS。本文件下方云方案仅保留研究，**非当前实施项、非R4前置**；不应用、不归档合并到主规格，实施任务保持未完成，云功能验收保持NOT_RUN。云专用2784MiB重分配与V15候选不生效，迁移号不预占；本地完整2880MiB基线继续计入sandbox/runtime/helper。

## MODIFIED Requirements

### Requirement: Bounded and safe data plane
The platform SHALL use fixed non-root trusted helpers with no network or credentials, descriptor-relative no-follow file access, NFC paths and real XFS mount/quota checks. Bytes SHALL use bounded streams or verified bounded SDK chunks rather than full-file arrays or Base64 JSON. Cloud working copies SHALL preserve the same file authority and per-file receipts through manifest/baseVersion/fence synchronization; cloud SDK path APIs alone SHALL NOT replace safe file-kind/path verification.
#### Scenario: Link and special-file rejection
- **WHEN** a path races a link or identifies a nonregular file
- **THEN** the operation fails without crossing the workspace root
#### Scenario: Quota exhaustion
- **WHEN** byte, inode or atomic-replacement temporary capacity is exhausted
- **THEN** the operation reports a stable failure and retains old content and accurate accounting
#### Scenario: Existing cloud copy after a file commit
- **WHEN** a public upload/replacement changes the authoritative file watermark
- **THEN** the cloud copy becomes dirty and execution/collection refreshes or rejects the old baseVersion without overwriting the new file
#### Scenario: Cloud provider unavailable
- **WHEN** the sandbox provider is down or the environment is stopped/deleted
- **THEN** existing authorized public file operations continue using their own dependencies and do not allocate a cloud instance
