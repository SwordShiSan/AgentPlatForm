## ADDED Requirements

### Requirement: Committed safe point authority
The platform SHALL restore only complete, compatible, immutable checkpoints whose SDK state and canonical execution ledger agree.
#### Scenario: Input checkpoint before physical request
- **WHEN** authorized execution reaches INPUT_READY
- **THEN** complete frozen input and a real SDK state clone commit atomically before any provider request, including a zero-round watermark
#### Scenario: Damaged or advanced checkpoint
- **WHEN** state is missing, a hash/bundle/codec differs, or later execution facts invalidate the snapshot
- **THEN** restoration is refused without selecting an older snapshot or starting an empty-context run
#### Scenario: Successful operation recovery
- **WHEN** a new attempt restores a checkpoint containing a successful deterministic tool result
- **THEN** task/logical-turn/operation identities and the result remain stable and the side effect is not executed again

### Requirement: Stopped executor and reliable handoff
The platform SHALL require verified prior termination and retain the unique transactional outbox/MQ ownership path for manual checkpoint recovery.
#### Scenario: Owner not proven stopped
- **WHEN** only lease expiration or Future cancellation is available
- **THEN** the slot is not transferred and no checkpoint continuation is dispatched
#### Scenario: Concurrent recovery and delivery
- **WHEN** commands or messages repeat or two workers compete
- **THEN** command/revision fencing admits one generation and owner, state copying commits before ACK, and old-fence writes fail
#### Scenario: Cancellation or newer conversation work
- **WHEN** a task was cancelled, authorization is revoked, or later independent conversation work was submitted
- **THEN** recovery is refused under the same ordered transaction locks

### Requirement: Task-wide bounded continuation
The platform SHALL preserve task-wide physical-request, deadline and accounting limits across attempts without replaying unknown model or tool effects.
#### Scenario: Physical call becomes unknown
- **WHEN** a started request or tool lacks a definitive result
- **THEN** continuation remains blocked with UNKNOWN and its budget hold intact
#### Scenario: Remaining allowance or time exhausted
- **WHEN** the four-call allowance, first-execution120second deadline or finite budget cannot admit continuation
- **THEN** no new request starts and historical usage is not reset or zero-filled
#### Scenario: Complete final round
- **WHEN** the stopped task has a committed definitive final round
- **THEN** the existing result converges idempotently without another model request

### Requirement: Operational recovery and authorized projection
The platform SHALL accept recovery mutations only through protected operator commands and expose only an authorized read-only user projection.
#### Scenario: User observes recovery
- **WHEN** the owning user refreshes or resumes SSE during recovery
- **THEN** stage/reason/diagnostic metadata and original message identities remain consistent without repeated text, new task submission or automatic UNKNOWN retry
#### Scenario: Foreign identity or unsafe command input
- **WHEN** a foreign user/administrator reads the task, or operator input has wrong versions, command hash or file protection
- **THEN** access or mutation is rejected without exposing state, credentials or internal evidence

### Requirement: Evidence-bound verification and release
The platform SHALL distinguish local verification from cloud release and promote only the accepted immutable candidate with tested recovery and rollback evidence.
#### Scenario: Local completion
- **WHEN** real PG/MQ/two-worker, SDK, browser, migration, refusal and1800second observation gates pass on the frozen candidate
- **THEN** protected step-to-phase-to-main PRs may mark R2.4 VERIFIED while R2 remains unreleased
#### Scenario: Cloud acceptance or failure
- **WHEN** the cloud capacity/backup/deploy/observation/isolated-restore/R1-rollback gates run
- **THEN** only all PASS permits a production-r2 tag; failure restores R1, retains evidence and never upgrades unrun results
#### Scenario: Unsafe host window
- **WHEN** a source project is still active during isolated restore, a host path overlaps the source, or another window action holds the lock
- **THEN** refuse before restoring data or starting a temporary service, preserve the original XFS UUID and never infer release from prepared or imported files
#### Scenario: Inherited production grants or external resource usage
- **WHEN** R0/R1 defaults granted the API whole-table checkpoint/state access, or another project has an unlimited container
- **THEN** revoke broad private access before granting only metadata, verify from inherited ACLs, and count every coexisting container or refuse resource admission
#### Scenario: Reuse definitive supplier evidence after a host-test failure
- **WHEN** a prior window has the same application source, a successful task and exact known persisted usage
- **THEN** verify its identity/configuration and reuse it without submitting another task, while refusing UNKNOWN, missing usage or drift and using the declared SSE media type
#### Scenario: Non-root restore probe under a protected parent
- **WHEN** the trusted host runs a quota child inside a0700 restore root
- **THEN** enter the verified workspace before dropping privileges, operate relatively as UID10001, retain0700 protection and independently verify both quota limits
