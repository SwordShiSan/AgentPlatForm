## ADDED Requirements
### Requirement: Transactional queued submission
The platform SHALL atomically persist tenant-bound messages, task, budget, outbox and idempotency result. Only RabbitMQ delivery SHALL initiate durable claim, which SHALL commit attempt/fence/inbox before ACK. One global slot and conversation order SHALL be enforced.
#### Scenario: Response or confirm is lost
- **WHEN** submission or publish confirmation is retried
- **THEN** the same task/event is reused and at most one valid owner exists
#### Scenario: Broker or database fails
- **WHEN** dependencies are unavailable
- **THEN** broker failure preserves bounded queued work without DB direct execution, and DB failure stops admission/claim/calls

### Requirement: Fenced calls and bounded budget
Each physical call SHALL have a unique durable identity/reservation and current ownership check. A task SHALL have at most4 physical calls and120s, pre-output retries at most2 and empty repair at most1. Unknown calls SHALL retain reconciliation reservations without replay.
#### Scenario: Owner expires after call start
- **WHEN** a started call loses its execution lease
- **THEN** further actions/commits are rejected, its outcome remains UNKNOWN until reconciliation, and the old owner must be stopped before slot release
#### Scenario: Concurrent budget requests
- **WHEN** competing submissions exceed remaining quota
- **THEN** committed reservations never exceed the configured limit

### Requirement: Durable real streaming and context
The platform SHALL publish only committed text batches using Unicode codepoint offsets and consistent snapshot cursors. Required context SHALL fit explicit8192/1024/safety limits or block; no hidden reasoning, UI or control content SHALL enter public messages.
#### Scenario: Refresh or duplicate events
- **WHEN** a browser reconnects during output
- **THEN** snapshot/replay yields the same text without duplication and disconnect does not cancel the task
#### Scenario: Partial provider failure
- **WHEN** the provider disconnects after output
- **THEN** persisted partial text remains visible, no transparent retry occurs, and the task is not successful

### Requirement: Scoped credentials and access
Model credentials SHALL be AES256GCM encrypted with PLATFORM or USER scope, authenticated owner/space binding and independent external secret master key; only the trusted worker/operator MAY access plaintext for the provider exception. Every chat read/write/SSE/cancel/usage operation SHALL check current user/space access; writes SHALL validate Origin/CSRF.
#### Scenario: Foreign references or credential tampering
- **WHEN** another space is selected or ciphertext/AAD is altered
- **THEN** access or decryption is rejected before any model call, without leaking secrets
#### Scenario: Platform and user credential sources
- **WHEN** a task resolves its frozen credential binding
- **THEN** USER credentials require the exact owner/space, PLATFORM credentials require explicit operator-authorized model binding, and failure never silently switches sources
#### Scenario: Revoked or stale credential
- **WHEN** an operator revokes a credential or its revision differs from the frozen task configuration
- **THEN** model discovery or the worker's next credential resolution rejects it, with no plaintext returned through API or audit records
#### Scenario: Trusted key import
- **WHEN** the offline operator imports a platform or user key
- **THEN** it accepts only a private input file, encrypts before database insertion, keeps the master key outside the database and refuses secrets in process arguments

### Requirement: Local constrained acceptance
The platform SHALL use the approved DeepSeek model and real RabbitMQ/PostgreSQL acceptance under one execution slot. Per-service memory MAY be redistributed within2887MiB enabled-container limit and3655MiB host budget. Real model acceptance SHALL stop at20 calls or1 CNY. Cloud production SHALL remain R0.
#### Scenario: Model test cap reached
- **WHEN** real acceptance consumes its request or cost cap
- **THEN** further physical calls are denied and incomplete cases remain NOT_RUN

