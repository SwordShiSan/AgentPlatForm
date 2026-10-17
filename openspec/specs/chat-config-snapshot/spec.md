# chat-config-snapshot Specification

## Purpose
TBD - created by archiving change r1-3-chat-config-snapshot. Update Purpose after archive.
## Requirements
### Requirement: Atomic first submission
The platform SHALL keep new-chat drafts local until first submission and SHALL atomically persist conversation, messages, task, configuration, reservation and outbox. Idempotency SHALL bind the exact submitted body.
#### Scenario: Response loss
- **WHEN** the identical initial request is retried
- **THEN** the original conversation and task are returned without additional reservation or dispatch
#### Scenario: Admission failure
- **WHEN** credential, authorization or budget admission fails
- **THEN** no conversation or partial task is committed and the draft remains available

### Requirement: Frozen effective configuration
The platform SHALL resolve platform defaults, personal defaults and explicit selection in order, validate live constraints, and freeze model/version/source/credential revision/prices/budget/bundle/hash per task. Preference writes SHALL use version preconditions.
#### Scenario: Selection changes after submission
- **WHEN** a user changes model or credential source after a task is queued
- **THEN** the queued task retains its original configuration and subsequent tasks use the new selection
#### Scenario: Unsupported capability or stale preference
- **WHEN** a request asks for unavailable tools or updates a stale preference version
- **THEN** it is rejected without silently downgrading or overwriting concurrent changes

### Requirement: Scoped encrypted credential lifecycle
The platform SHALL permit owners to create, replace and revoke their USER DeepSeek credential without returning secret material, SHALL retain PLATFORM management under operations, and SHALL never fall back between credential sources. The dedicated API credential service SHALL encrypt using an independently mounted master key; the worker SHALL decrypt only for an authorized call.
#### Scenario: Replacement and revocation
- **WHEN** a user replaces a credential
- **THEN** the previous record is revoked, historical task references remain unchanged and no later physical call may use the revoked record
#### Scenario: Forged ownership
- **WHEN** another user or an administrator attempts to access the user's key or private task configuration
- **THEN** access is denied without exposing metadata or secret content

### Requirement: Local bounded verification
The platform SHALL expose only public message blocks and sanitized audit correlation, preserve all R1.2 execution protections, and verify both Flash and Pro locally within the existing shared twenty-call and one-CNY acceptance meter.
#### Scenario: Unknown prior charges
- **WHEN** R1.3 acceptance starts
- **THEN** existing physical call counts and UNKNOWN reservations remain accounted for and are not reset
#### Scenario: Browser lifecycle
- **WHEN** a user refreshes a draft, reconnects a stream or changes accounts
- **THEN** drafts and persisted events remain isolated, accepted work is not duplicated, and logout or identity expiry clears local private state

