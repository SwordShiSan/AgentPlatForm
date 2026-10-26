> **DEFERRED / 保留方案，暂不实施（2026-10-26）**
> 用户因成本决定继续现行本地Docker＋gVisor/runsc、XFS、受信文件helper和SeaweedFS。本文件下方云方案仅保留研究，**非当前实施项、非R4前置**；不应用、不归档合并到主规格，实施任务保持未完成，云功能验收保持NOT_RUN。云专用2784MiB重分配与V15候选不生效，迁移号不预占；本地完整2880MiB基线继续计入sandbox/runtime/helper。

## MODIFIED Requirements

### Requirement: Durable scoped environment lifecycle
The platform SHALL persist environment identity, capacity, owner, outbox and idempotency before dispatch, retain storage readiness separately, and expose authorized lifecycle using the current public contracts. R3.C SHALL bind provider/region/template/sandbox identities through additive facts, keep historical local commands queryable and never replay them into a cloud generation.
#### Scenario: Absent environment read
- **WHEN** a member reads a storage-ready workspace without environment
- **THEN** NOT_CREATED revision0 is returned without allocation
#### Scenario: Concurrent preparation and capacity
- **WHEN** identical requests or competing consumers race for the single sandbox slot
- **THEN** one stable operation and selected-provider physical resource exist, with no partial reservation on rollback
#### Scenario: Lost response or unknown owner
- **WHEN** delivery/ACK/response is lost or owner expiry cannot prove effects stopped
- **THEN** original command is queried, BLOCKED retains the slot, and no second create/start or blind retry occurs
#### Scenario: Definite retry or stopped generation
- **WHEN** a failed pre-effect operation is retried or a stopped environment is prepared
- **THEN** version/idempotency/live authorization are checked and a new physical generation is admitted only after old termination is proven

### Requirement: Authenticated runner and fixed sandbox isolation
The runner SHALL use independently signed versioned internal requests/results and a fixed verified selected-provider policy without exposing management authority to the sandbox. R3.C cloud execution SHALL use controlled provider HTTPS/Java SDK access, exact provider identity, scoped files and independently verified ingress/egress/resource bounds. Authoritative local storage SHALL still require XFS UUID/project quota; the former runsc and host/guest PID policy remains evidence for the historical local profile only.
#### Scenario: Guest process headroom
- **WHEN** the historical local-profile fixed workload exhausts its32-task guest allowance under host PID128
- **THEN** fork is explicitly refused, callbacks terminate and the exact sandbox remains normally stoppable; a host-only fallback is recorded separately and cannot make this normal-stop scenario pass
#### Scenario: Signature or identity invalid
- **WHEN** algorithm/time/audience/body/path/policy/generation/fence or result signature is altered
- **THEN** no effect or successful result is accepted
#### Scenario: Runtime or mount invalid
- **WHEN** selected-provider runtime/config/template/policy or required authoritative mount/UUID/quota is absent or mismatched
- **THEN** preparation fails closed without local execution fallback or ordinary-directory writes
#### Scenario: Ready environment
- **WHEN** exact selected-provider identity, file manifest and real bounded application canary agree
- **THEN** only environment becomes READY, tied to its physical identity and generation, without enabling public shell or new tool capability

### Requirement: One authoritative bounded data plane
The platform SHALL run fixed protected file and execution probes against the current workspace with exclusive scoped leases and bounded results, including query-only reconciliation of an existing use. Cloud working copies SHALL use input/output manifests, exact versions and bounded synchronization; existing public file access remains independent of environment readiness.
#### Scenario: Original control caller already ended
- **WHEN** an authorized protected reconciliation command addresses the existing use after its deadline
- **THEN** only original signed queries/durable facts and guarded completion are used, foreign actors/workspaces are rejected, and no second POST, ownership transfer or program execution occurs
#### Scenario: Cross-runtime persistent data
- **WHEN** Python writes, Node reads/writes and application file port reads, then environment is stopped and rebuilt
- **THEN** the same confirmed workspace data/hash survives via verified synchronization and execution666 requires actual exit0 and output; no stale cloud copy can overwrite a newer file version
#### Scenario: Unsafe paths or revoked access
- **WHEN** traversal, symlink race, hardlink, device, foreign space, revoked actor or stale fence is supplied
- **THEN** access/effect/result commit is denied without exposing host or foreign data
#### Scenario: Output and resource exhaustion
- **WHEN** stdout/stderr, runtime deadline, memory/CPU/PIDs or XFS byte/inode limits are reached
- **THEN** bounds are enforced, truncation/failure is explicit and the platform remains alive

### Requirement: Proven stopping and bounded idle release
The platform SHALL distinguish cancellation request from actual termination and release capacity only after durable stopped facts. For cloud execution, local runner/transport/SDK shutdown SHALL NOT establish remote termination; unsynchronized output and uncertain remote effects retain blocking facts. Collection SHALL precede destructive termination and remaining TTL/renewal SHALL be admitted for collection.
#### Scenario: Cancel or authority failure
- **WHEN** timeout/cancellation/revocation/DB failure occurs during a protected execution
- **THEN** new operations stop, exact provider sandbox termination is verified together with command/writer and callback exit, and unknown termination keeps BLOCKED/slot
#### Scenario: Idle cleanup
- **WHEN** no operation is active for60seconds
- **THEN** durable stop is dispatched, stopped receipt precedes removal/slot release, and workspace files/quota remain
#### Scenario: Stale or forged stop proof
- **WHEN** stop evidence belongs to another physical resource/generation or cannot be verified
- **THEN** no capacity release or replacement execution is allowed

### Requirement: Separate trusted file bridge and helper facts
The platform SHALL perform private fixed file operations in a pinned non-root trusted helper, keep every untrusted program on the verified selected sandbox provider, and persist helper intent and exact physical identity under the original use. V12 remains immutable and V15 provider/sync facts SHALL be additive; helper output SHALL NOT create a second file result authority. The retained local trusted storage helper SHALL NOT execute user programs or become a cloud-outage business-execution fallback.
#### Scenario: File request and isolation
- **WHEN** a protected file use starts
- **THEN** helper intent commits before create, exact container binds before start, exec identity commits before execStart, and helper is limited to64MiB/32PIDs/CPU1 with one work mount and no network/capability/device/socket/credential or user program
#### Scenario: Response loss and physical uncertainty
- **WHEN** create/start/result response is lost or an original helper intent already exists
- **THEN** only the original identity is queried, no second helper or replayed write is allowed, and missing or ambiguous physical facts retain BLOCKED and capacity
#### Scenario: Helper completion and stop
- **WHEN** a file output or cancellation is committed
- **THEN** exact helper stop and callback termination are persisted before success; sandbox termination is separately required before environment slot release, and only durable facts authorize container cleanup
#### Scenario: Cross-boundary filesystem attack
- **WHEN** an isolated selected-provider program renames directories or substitutes soft/hard links while trusted file IO or output collection runs
- **THEN** outside markers remain unchanged and unsafe paths/kinds are rejected; in-process path checks or an unverified remote pathname alone do not satisfy this scenario
#### Scenario: Authority loss or replacement runner
- **WHEN** membership/owner/fence/DB authority is lost or another runner observes the use
- **THEN** new effects and ordinary result commits are refused, precise stop evidence may converge through the original use, and replacement observation requires valid prior-runner stop proof

#### Scenario: Retired exec record after exact container termination
- **WHEN** Docker no longer retains the durably bound helper exec after the original container terminates
- **THEN** absence alone cannot release capacity; only an exact unpaused stopped container with zero PID and valid start/finish chronology, callback termination and any required original-runner proof may establish stopping, with outcome UNKNOWN and no replay
