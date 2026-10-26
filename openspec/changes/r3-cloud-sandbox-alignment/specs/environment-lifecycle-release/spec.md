> **DEFERRED / 保留方案，暂不实施（2026-10-26）**
> 用户因成本决定继续现行本地Docker＋gVisor/runsc、XFS、受信文件helper和SeaweedFS。本文件下方云方案仅保留研究，**非当前实施项、非R4前置**；不应用、不归档合并到主规格，实施任务保持未完成，云功能验收保持NOT_RUN。云专用2784MiB重分配与V15候选不生效，迁移号不预占；本地完整2880MiB基线继续计入sandbox/runtime/helper。

## MODIFIED Requirements

### Requirement: Durable authorized lifecycle
The platform SHALL accept versioned idempotent sleep, wake and environment-only deletion through the existing persistent queue chain, with current identity and space authorization. Cloud sleep/delete SHALL preserve the existing product semantics through collection and exact instance termination; wake SHALL create a new generation from persistent files, without depending on provider pause/snapshot or automatic resume.
#### Scenario: Sleep and wake
- **WHEN** an authorized user sleeps a ready environment and later explicitly wakes it
- **THEN** sleep confirms collection and exact selected-provider stopping/removal, wake creates one new generation and READY requires real storage/manifest/canary evidence
#### Scenario: Duplicate or stale request
- **WHEN** an original request is repeated, its content changes or its version is stale
- **THEN** the same request returns the original operation, changed content is rejected409 and stale versions412 without another physical command
#### Scenario: Foreign administrator
- **WHEN** an administrator targets another user's environment
- **THEN** access is denied without container or path disclosure

### Requirement: Stop and cleanup are facts
The platform SHALL persist exact stopped execution facts before cleanup/admission release and SHALL retain unresolved ownership without replay.
#### Scenario: Lost stop response
- **WHEN** the selected-provider operation stops but its response is lost
- **THEN** reconciliation queries the original command and settles its receipt once without replaying it
#### Scenario: Cleanup failure
- **WHEN** removal fails after the stop receipt commits
- **THEN** durable cleanup state remains observable and exact cleanup can converge without another business effect
#### Scenario: Unproven termination
- **WHEN** only lease expiry, transport close, SDK release, local runner stop or provider404 is known
- **THEN** the environment remains BLOCKED with retained ownership until exact selected-provider stop evidence and callback exit are available; host proof may prove only its own local process and cannot prove remote instance termination

### Requirement: Signed protocol compatibility
ENVIRONMENT_CLOUD_V1 SHALL bind cloud intent/provider identity and synchronization policy through the existing internally signed runner boundary. Historical ENVIRONMENT_V2/V3 validation and query facts SHALL remain readable without replay or changes to V1-V14. Provider API authentication remains separate from internal RS256 signing.
#### Scenario: Forged or old owner
- **WHEN** an algorithm, audience, body, policy, generation or fence is wrong
- **THEN** runner refuses execution and result submission
#### Scenario: Additive upgrade
- **WHEN** V14 upgrades to V15
- **THEN** previous files/runtime baseline and supported old-program read paths remain valid without changing V1-V14 or inventing Docker IDs for cloud facts

### Requirement: Whole-stack bounded resources
The platform SHALL validate the complete local cold-start/file/SDK profile within2887MiB total ceilings,768MiB host reserve and5GiB disk margin, and account selected cloud sandbox resources/cost separately. Local module ceilings MAY be reallocated only after the local business sandbox/runtime stops; SDK buffers, helper and temporary processes SHALL still count. The proposed2784MiB local profile is not a verified capacity promise.
#### Scenario: Concurrent required capabilities
- **WHEN** login,100MiB bounded streaming and trusted sandbox canary execute in the admitted profile
- **THEN** required services stay alive and every helper/runtime budget is counted
#### Scenario: Insufficient resources
- **WHEN** required functionality cannot fit or a floor fails
- **THEN** startup/release stops with FAIL evidence, without swap expansion or weakened isolation
