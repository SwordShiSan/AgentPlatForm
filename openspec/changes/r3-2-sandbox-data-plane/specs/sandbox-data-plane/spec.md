## ADDED Requirements

### Requirement: Durable scoped environment lifecycle
The platform SHALL persist environment identity, capacity, owner, outbox and idempotency before dispatch, retain storage readiness separately, and expose authorized prepare/read/definite-retry only.
#### Scenario: Absent environment read
- **WHEN** a member reads a storage-ready workspace without environment
- **THEN** NOT_CREATED revision0 is returned without allocation
#### Scenario: Concurrent preparation and capacity
- **WHEN** identical requests or competing consumers race for the single sandbox slot
- **THEN** one stable operation and physical container exist, with no partial reservation on rollback
#### Scenario: Lost response or unknown owner
- **WHEN** delivery/ACK/response is lost or owner expiry cannot prove effects stopped
- **THEN** original command is queried, BLOCKED retains the slot, and no second create/start or blind retry occurs
#### Scenario: Definite retry or stopped generation
- **WHEN** a failed pre-effect operation is retried or a stopped environment is prepared
- **THEN** version/idempotency/live authorization are checked and a new physical generation is admitted only after old termination is proven

### Requirement: Authenticated runner and fixed sandbox isolation
The runner SHALL use independently signed versioned requests/results, pinned image/runsc policy and verified XFS UUID/project quota without exposing management authority to the sandbox.
#### Scenario: Signature or identity invalid
- **WHEN** algorithm/time/audience/body/path/policy/generation/fence or result signature is altered
- **THEN** no effect or successful result is accepted
#### Scenario: Runtime or mount invalid
- **WHEN** runtime/config/image/capabilities/mount/UUID/quota is absent or mismatched
- **THEN** preparation fails closed without runc fallback or ordinary-directory writes
#### Scenario: Ready environment
- **WHEN** exact container inspect and real bounded host/guest canary agree
- **THEN** only environment becomes READY, tied to its physical identity and generation, with no public shell/file capability

### Requirement: One authoritative bounded data plane
The platform SHALL run fixed protected file and execution probes against the current workspace with exclusive scoped leases and bounded results.
#### Scenario: Cross-runtime persistent data
- **WHEN** Python writes, Node reads/writes and application file port reads, then environment is stopped and rebuilt
- **THEN** the same workspace data/hash survives and execution666 requires actual exit0 and output
#### Scenario: Unsafe paths or revoked access
- **WHEN** traversal, symlink race, hardlink, device, foreign space, revoked actor or stale fence is supplied
- **THEN** access/effect/result commit is denied without exposing host or foreign data
#### Scenario: Output and resource exhaustion
- **WHEN** stdout/stderr, runtime deadline, memory/CPU/PIDs or XFS byte/inode limits are reached
- **THEN** bounds are enforced, truncation/failure is explicit and the platform remains alive

### Requirement: Proven stopping and bounded idle release
The platform SHALL distinguish cancellation request from actual termination and release capacity only after durable stopped facts.
#### Scenario: Cancel or authority failure
- **WHEN** timeout/cancellation/revocation/DB failure occurs during a protected execution
- **THEN** new operations stop, exact sandbox termination is verified together with exec/callback exit, and unknown termination keeps BLOCKED/slot
#### Scenario: Idle cleanup
- **WHEN** no operation is active for60seconds
- **THEN** durable stop is dispatched, stopped receipt precedes removal/slot release, and workspace files/quota remain
#### Scenario: Stale or forged stop proof
- **WHEN** stop evidence belongs to another container/generation or cannot be verified
- **THEN** no capacity release or replacement execution is allowed

### Requirement: Verified stage delivery without production rollout
The platform SHALL verify real persistence/broker/browser/Linux boundaries and preserve released R2 after independent cloud acceptance.
#### Scenario: Native window and restoration
- **WHEN** cloud-only gates run
- **THEN** original R2 stops serially, independent test resources are bounded, and exact R2 identities/policies/V9/UUID/public read-only functionality are restored with legacy stopped
#### Scenario: Final evidence and user interface
- **WHEN** all mandatory gates pass
- **THEN** protected PRs precede VERIFIED, archive/body/evidence hashes are validated after checkout, and UI exposes preparation/status without terminal/files or hidden UNKNOWN retry
