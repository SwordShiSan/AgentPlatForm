> **DEFERRED / 保留方案，暂不实施（2026-10-26）**
> 用户因成本决定继续现行本地Docker＋gVisor/runsc、XFS、受信文件helper和SeaweedFS。本文件下方云方案仅保留研究，**非当前实施项、非R4前置**；不应用、不归档合并到主规格，实施任务保持未完成，云功能验收保持NOT_RUN。云专用2784MiB重分配与V15候选不生效，迁移号不预占；本地完整2880MiB基线继续计入sandbox/runtime/helper。

## ADDED Requirements

### Requirement: Official Java provider boundary
The platform SHALL use the official Alibaba-maintained E2B Java SDK behind pure-Java provider ports for Speed Edition Economy in cn-hangzhou, while retaining the single AgentScope Harness and existing persistent scheduling. SDK source/artifact/dependencies SHALL be immutable and verified before use.
#### Scenario: API key data-plane access
- **WHEN** the selected provider is configured with a Sandbox API Key
- **THEN** only the controlled HTTPS data-plane endpoint/domain is used and no AK/STS control-plane SDK substitution or arbitrary endpoint is accepted
#### Scenario: Source-only official SDK
- **WHEN** a reproducible public Maven release is unavailable
- **THEN** an exact unmodified official source commit and built JAR/dependency hashes are recorded without following master or maintaining a third-party patch
#### Scenario: SDK limitation
- **WHEN** necessary streaming, termination or isolation cannot be demonstrated through supported bounded adapters
- **THEN** the affected capability remains failed/not-run and the technical choice is reviewed rather than removing a delivered function or modifying third-party source

### Requirement: Provider identity and one active binding
The platform SHALL bind provider, region, fixed template/version, policy, sandbox identity, workspace, generation and fence without forging Docker identity fields or sharing one existing base instance across spaces.
#### Scenario: Read-only existing instance
- **WHEN** an operator only inspects the original paused sandbox
- **THEN** list/getInfo is used without connect, automatic close/kill, wake or guest mutation
#### Scenario: New provider migration
- **WHEN** V15 adds cloud bindings and operations
- **THEN** V1-V14 remain unchanged, old Docker facts remain readable and no cloud ID is padded into a Docker digest/container column
#### Scenario: Generation competition
- **WHEN** two commands or consumers compete for the active cloud slot
- **THEN** only one authorized workspace/generation owns execution and stale bindings cannot start or submit results

### Requirement: Persistent remote effects and uncertain outcomes
Every physical create/execute/terminate request SHALL have durable intent before sending and exact queryable results or retained uncertainty. The platform SHALL keep outbox, confirm, durable claim and ACK ordering without SDK retries hiding extra effects.
#### Scenario: Lost create or command response
- **WHEN** the provider may have accepted a request but the result is unavailable
- **THEN** only the original resource/operation is discovered or queried and no second create/execute occurs without reliable nonacceptance proof
#### Scenario: Missing provider idempotency
- **WHEN** the SDK/provider cannot uniquely discover an ambiguous original request
- **THEN** state remains BLOCKED with the associated reservations and operator evidence, not a newly issued request
#### Scenario: Authority revoked
- **WHEN** current membership, credential, lease or fence becomes invalid
- **THEN** new effects and ordinary result commits stop and original stopping/reconciliation facts remain separately recordable

### Requirement: Authoritative files and versioned synchronization
The platform SHALL retain XFS current-file authority, database projections and private S3 immutable versions. Cloud files SHALL be temporary working copies transferred under canonical manifests, exclusive workspace leases and baseVersion/fence checks.
#### Scenario: Prepare and execute
- **WHEN** a cloud generation is prepared or an operation starts
- **THEN** its current input watermark/path/kind/length/hash is verified before execution and temporary/cloud disk size is not treated as a durable workspace quota
#### Scenario: Public file changes after readiness
- **WHEN** upload or explicit replacement commits while the cloud environment exists
- **THEN** the cloud watermark becomes dirty and the next execution or collection synchronizes/rechecks versions rather than using stale input or overwriting the newer upload
#### Scenario: Collect outputs
- **WHEN** remote writers are proven quiescent
- **THEN** authorized bounded output is checked for safe paths/kinds/quota and committed through existing per-file atomic replacement, immutable version, reference/accounting and durable receipts
#### Scenario: Partial batch or conflict
- **WHEN** some physical file commits succeed but collection fails or a baseVersion changed
- **THEN** each original child operation remains queryable and the batch cannot claim atomic completion, repeat a committed write or silently overwrite content

### Requirement: Bounded cloud IO and working storage
The platform SHALL preserve100MiB file support,2MiB strict-UTF8 preview, single-range immutable downloads,2GiB/100000inode authoritative workspace quotas, and bounded cloud working-directory/output admission. Whole-file SDK buffering SHALL NOT bypass local memory or request limits.
#### Scenario: Large file
- **WHEN** a100MiB file crosses the SDK/provider boundary
- **THEN** supported bounded chunks or verified streaming keep request/buffer limits, total length/hash and cleanup, without a full-file byte array or Base64 JSON
#### Scenario: Malformed or unsafe data
- **WHEN** length/hash/chunk sequence, NFC identity, file kind, link safety or available capacity is invalid
- **THEN** the original operation records explicit failure/uncertainty and cannot publish a confirmed version or cross the workspace boundary
#### Scenario: Remote working quota
- **WHEN** guest output or inode consumption reaches the admitted working-directory ceiling
- **THEN** the bound is enforced before uncontrolled growth and local persistence quota does not falsely stand in for remote enforcement

### Requirement: Remote stopping and retained lifecycle semantics
Sleep and environment-only deletion SHALL close admission, quiesce remote writers, collect/persist files, terminate the exact remote instance and save stop/cleanup facts. Wake SHALL restore files to a new generation. Provider pause/snapshot/automatic resume SHALL NOT be required for this product behavior.
#### Scenario: Sleep or delete and wake
- **WHEN** lifecycle completes and a user later prepares/wakes
- **THEN** files, versions and reservations remain, new generation verifies its manifest, and temporary process memory/system changes are not promised to survive
#### Scenario: SDK cancellation or transport loss
- **WHEN** only Future cancel, release, close, kill-request response, local runner stop,404 or lease expiry is known
- **THEN** remote stopping is not inferred and unresolved capacity remains blocked until exact provider facts and callback/collection exit are verified
#### Scenario: Idle environment
- **WHEN** no operation is active for60seconds
- **THEN** the same persistent synchronization/termination path runs without deleting user files or releasing an unproven remote slot
#### Scenario: TTL approaching
- **WHEN** remaining provider TTL cannot cover execution plus worst-case2GiB/100000-file collection/retries and safety margin or confirmed renewal fails
- **THEN** new execution is refused and early stop/collection begins; expiry with uncollected output is recorded as unsynchronized failure, not persistence success

### Requirement: Verified cloud access policy and secret ownership
Only the trusted provider adapter SHALL read the cloud management credential. Templates and SDK configurations SHALL explicitly enforce approved ingress/egress and resource policies, with tokens redacted and no silent local execution fallback.
#### Scenario: Access policy
- **WHEN** an instance is created
- **THEN** controlled secure access and denied public user ports/guest egress are explicitly configured and verified for that template/runtime rather than accepted from defaults or an update200 response
#### Scenario: Credential exposure
- **WHEN** SDK responses, events, logs, diagnostics, snapshots or browser projections are produced
- **THEN** provider API keys and envd/traffic tokens are excluded and no persistent cloud credential enters guest code or model context
#### Scenario: Cloud outage
- **WHEN** region, balance, quota or provider connectivity prevents execution
- **THEN** identity/chat/file APIs remain independently usable where their own dependencies permit, while cloud work queues/fails truthfully without local runc/gVisor fallback

### Requirement: Separate local memory and cloud cost budgets
The platform SHALL allow local module ceilings to increase after local business sandbox shutdown, count SDK buffers/helpers locally and account cloud resources separately while retaining single-slot admission.
#### Scenario: Revised host profile
- **WHEN** the old local business sandbox/runtime reserve is actually removed
- **THEN** the proposed API512/worker512/runner384/PG320/MQ256/Redis64/gateway32/Seaweed640/helper64 profile totals2784MiB, remains dynamically adjustable within2887MiB and preserves768MiB host/5GiB disk reserve without swap expansion
#### Scenario: Remote instance resource/cost
- **WHEN** a cloud instance is active, paused, unknown or destroyed
- **THEN** exact spec/QoS/price/time and estimated or pending cost are recorded independently of host and model budgets, without increasing execution concurrency

### Requirement: Compatible migration and explicit rollback
The platform SHALL migrate through additive provider facts and drained ownership, preserve current R3 data/artifacts and verify old-program reads before release. No provider transition SHALL enable two active write paths.
#### Scenario: Rollback after cloud writes
- **WHEN** an explicit operator rollback is required
- **THEN** remote effects/files/stopping are reconciled first, cloud rows are isolated from Docker-only readers, environment mutation enters maintenance and old R3 identity/chat/files remain readable without restoring an old DB over new business writes
#### Scenario: Planning-only turn
- **WHEN** plan/specification checks pass before implementation
- **THEN** productionR3 stays unchanged, feature tests stay NOT_RUN and R4 remains waiting for R3.C verification
