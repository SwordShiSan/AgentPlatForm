## ADDED Requirements

### Requirement: Fenced execution writes
Every execution mutation SHALL atomically validate live actor/space authorization, task, attempt, owner, generation, fence and database-time lease. SDK CAS and deletion SHALL use the same guard. Lock order SHALL be slot, conversation, task/dispatch, attempt, call/ledger/budget.

#### Scenario: Old owner writes
- **WHEN** an old fence attempts heartbeat, text, accounting, result or SDK state mutation
- **THEN** every mutation is rejected without changing data

#### Scenario: Authorization removed
- **WHEN** an account, membership or frozen credential is revoked
- **THEN** subsequent calls and execution writes fail closed

### Requirement: Durable single-owner handoff
The system SHALL persist inbox, attempt, dispatch, conversation ownership and the global slot before ACK. Two workers SHALL share one execution slot. Redelivery SHALL NOT create a second owner, and broker failure SHALL NOT enable database-direct execution.

#### Scenario: ACK or confirm lost
- **WHEN** confirmed delivery or ACK is lost and the message is delivered again
- **THEN** durable identities prevent a second execution and conversation order is preserved

#### Scenario: Claim transaction fails
- **WHEN** claim persistence fails
- **THEN** the delivery remains unacknowledged

#### Scenario: Reclaimed task event version
- **WHEN** a safely requeued task is claimed under a new generation
- **THEN** RUNNING and message-start events use their actual persisted entity versions instead of the first claim's constants

#### Scenario: Reconnected consumer callbacks
- **WHEN** a new AMQP channel delivers while the previous callback is still cleaning up
- **THEN** that worker serializes claim, ACK and execution cleanup before admitting the next callback, while retaining the distributed database fence

### Requirement: Canonical model and operation facts
The system SHALL establish a logical round before physical requests and link each call separately. A successful complete round, full tool call list, known usage settlement and associated events SHALL commit atomically at the Harness round boundary. Unknown usage SHALL retain reconciliation holds.

#### Scenario: Crash after response before commit
- **WHEN** a request started but the full round transaction did not commit
- **THEN** recovery treats the request as UNKNOWN and never automatically repeats it

#### Scenario: Tool execution boundaries
- **WHEN** an internal test tool executes
- **THEN** RUNNING is durable before execution and the result is committed before returning to the SDK

### Requirement: Immutable checkpoint evidence
Committed checkpoints SHALL contain immutable SDK-state bytes, key/version/hash, canonical watermark and exact runtime bundle/component/codec/state versions. All restore checks SHALL precede copying to a new attempt namespace. Existing R1 rows SHALL NOT be fabricated into checkpoint history.

#### Scenario: Missing or incompatible state
- **WHEN** a snapshot is missing, corrupt or incompatible with its frozen bundle
- **THEN** restoration is rejected without an empty-context fallback

#### Scenario: State changes after checkpoint
- **WHEN** a mutable state slot is subsequently updated
- **THEN** the committed checkpoint remains byte-for-byte unchanged

### Requirement: Proven stopped-owner reconciliation
Recovery SHALL require trusted host proof binding owner UUID, exact container ID, image, start and verified stop times, plus an expired lease. A bare confirmation flag SHALL NOT authorize recovery. API and workers SHALL NOT receive Docker sockets or operator credentials.

#### Scenario: Safe requeue or convergence
- **WHEN** the original owner is proven stopped
- **THEN** definitely unstarted work can requeue via a new dispatch generation or a committed terminal round can finalize, while unknown requests remain BLOCKED with holds

#### Scenario: Invalid proof
- **WHEN** a proof is absent, forged, stale or bound to another instance
- **THEN** reconciliation and slot release are rejected

### Requirement: Local-only evidence
R2.1 acceptance SHALL use isolated PostgreSQL, RabbitMQ, two actual workers and deterministic providers with zero real-provider calls. Total container limits SHALL be at most 2887MiB with 768MiB host reserve, one execution and one password-hash slot. Cloud R1 SHALL remain unchanged.

#### Scenario: Incomplete gates
- **WHEN** a mandatory regression, crash, isolation or browser check has not passed
- **THEN** R2.1 stays IMPLEMENTING and cannot advance to R2.2
