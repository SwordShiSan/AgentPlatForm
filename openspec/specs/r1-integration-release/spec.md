# r1-integration-release Specification

## Purpose
TBD - created by archiving change r1-4-integration-release. Update Purpose after archive.
## Requirements
### Requirement: Correlated private failure diagnostics
The platform SHALL correlate request, task, outbox, attempt and call through a server-generated trace and SHALL expose owner-authorized sanitized diagnostics.
#### Scenario: Failed and partial task
- **WHEN** an authorized caller reads GET /api/v1/tasks/{id}/diagnostics after execution fails
- **THEN** the result includes stable classification, trace and partial-output/action facts without secrets or internal exception text
#### Scenario: Foreign task
- **WHEN** another user or platform administrator reads the owner's task diagnostics
- **THEN** access is rejected without disclosing the task
#### Scenario: Request failure
- **WHEN** authentication or submission fails before a task exists
- **THEN** Problem and sanitized failure logging use the same server-generated request trace without fabricated task or operation identities

### Requirement: Safe accounting and provider quota boundary
Acceptance SHALL preserve all prior metering and UNKNOWN reservations without fixed 20-call or 1-CNY blocking; business budgets SHALL remain enforced.
#### Scenario: Existing acceptance facts
- **WHEN** a permitted real call starts after the historical fixed acceptance threshold
- **THEN** cumulative accounting proceeds without reset and normal business admission remains required
#### Scenario: Remote quota exhausted
- **WHEN** the provider reports exhausted balance or quota
- **THEN** the task displays the specific failure and acceptance stops without retrying or rotating credentials to evade the limit

### Requirement: Revocation and stopped-owner integration
All R1 resources SHALL enforce current identity and space permission; UNKNOWN calls SHALL NOT be replayed and recovery SHALL require confirmed stopped ownership.
#### Scenario: Revocation during execution
- **WHEN** identity, membership or the frozen credential is revoked
- **THEN** new calls and result commits are rejected and the existing request converges to cancellation or UNKNOWN accounting
#### Scenario: Worker interruption
- **WHEN** an expired owner is proven stopped
- **THEN** reconciliation preserves unknown costs and releases the slot without sending the old call again

### Requirement: Explicit HTTP release and rollback
The release SHALL use the approved HTTP entry, HttpOnly SameSite=Lax non-Secure cookie, exact Origins and CSRF; HTTPS SHALL remain deferred.
#### Scenario: Cross-origin write
- **WHEN** a foreign Origin writes with a valid session
- **THEN** it is rejected while approved HTTP origins function
#### Scenario: Candidate deployment
- **WHEN** local mandatory gates and two-level PR promotion pass
- **THEN** the same immutable artifact is imported to cloud with no cloud build and R1 remains unreleased until cloud gates pass
#### Scenario: Restore and rollback
- **WHEN** consistent backup is restored in a separate serial project and R0 rollback is rehearsed
- **THEN** data, encrypted keys, hashes, quotas and readiness are verified before the same R1 candidate is finally released
#### Scenario: Resource or release failure
- **WHEN** disk/memory admission or a mandatory cloud gate fails
- **THEN** deployment stops or restores R0 without starting legacy business services or creating an R1 production tag

### Requirement: Public HTTP browser capability compatibility
The chat UI SHALL generate UUIDv4 idempotency identities from crypto.getRandomValues on public HTTP, without depending on secure-context-only randomUUID or Math.random. Missing clipboard support SHALL retain manual diagnostic copying.
#### Scenario: Insecure-context browser
- **WHEN** randomUUID and clipboard are unavailable while getRandomValues exists
- **THEN** first-message submission, idempotent replay and cancellation still function with correctly formed fresh UUIDs
#### Scenario: No cryptographic source
- **WHEN** no getRandomValues source exists
- **THEN** request identity generation fails explicitly without silently using a weak random source

### Requirement: Cold-start resource evidence
R1 SHALL fit all enabled container ceilings within2887MiB and preserve768MiB host reserve without swap extension. Dynamic per-module changes SHALL retain failed evidence and require exact configuration promotion and fresh constrained observation before release.
#### Scenario: Historical OOM hidden by a restart
- **WHEN** current OOMKilled is false but restart or kernel evidence shows an unplanned prior OOM
- **THEN** release is blocked and the failure is retained until a corrected candidate passes cold starts, observation and recovery
#### Scenario: Resource-only repetition
- **WHEN** unchanged application/provider artifacts already have successful persisted real-model evidence
- **THEN** the repeated observation may reuse exactly verified task/call/usage facts without additional provider calls and clearly distinguishes reused model evidence from the fresh resource window
