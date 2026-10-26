## ADDED Requirements

### Requirement: Atomic immutable task resource binding
The system SHALL atomically bind at most one authorized READY workspace and initial permission mode when accepting a task, without implicit workspace creation or execution, and SHALL preserve legacy task configuration hashes.

#### Scenario: Normal and legacy text submission
- **WHEN** an authorized user submits a task without workspace selection
- **THEN** the task has no workspace binding and defaults to RISK_BASED

#### Scenario: Bound submission failure or replay
- **WHEN** a bound submission fails or its identical idempotent request is repeated
- **THEN** all facts roll back or exactly the original task/binding/budget/outbox is returned

### Requirement: Bounded revocable task directory grants
The system SHALL accept only server-issued confirmed directory scopes and LIST/READ/WRITE capabilities, bind grants to the task and workspace, expire them within24hours or at task termination, and enforce realtime ACL and policy independently of FULL.

#### Scenario: Valid grant and revocation
- **WHEN** a user grants a directory then revokes it with current revision
- **THEN** subsequent not-started operations cannot use the revoked grant

#### Scenario: Invalid or stale scope
- **WHEN** a scope is foreign, unconfirmed, expired, stale or requests execution/network/delete capability
- **THEN** authorization is refused without changing grants or budget

### Requirement: Framework and transactional final authorization
The system SHALL keep AgentScope DEFAULT permission mode and empty confirmation rules, and SHALL repeat authorization inside the existing owned startTool transaction before admitting a tool operation.

#### Scenario: Revoke between check and execution
- **WHEN** permissions change after SDK permission checking but before startTool commits
- **THEN** the operation is denied or re-evaluated and no unauthorized effect starts

#### Scenario: Mandatory boundary under FULL
- **WHEN** FULL encounters revoked ACL, Plan restrictions, mandatory ASK/DENY or an unavailable capability
- **THEN** FULL cannot override that boundary

### Requirement: Versioned observable permissions
The system SHALL expose permissions, candidate scopes and grant changes through realtime-authorized APIs with version/idempotency protections, durable typed events and Vue Query projections.

#### Scenario: Concurrent or lost response mutation
- **WHEN** two browser tabs mutate one revision or a response is lost
- **THEN** stale mutation is rejected and retry retrieves the original decision without duplicate grant or event

#### Scenario: Terminal task and administrator isolation
- **WHEN** a task is terminal or an administrator requests another user's task
- **THEN** mutation is refused and administrator quota exemption does not bypass content ACL
