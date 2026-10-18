## ADDED Requirements

### Requirement: Framework-managed persistence
Business persistence SHALL use MyBatis Mapper/PO/XML and Spring-managed transactions while preserving space scope, lock order, database-time leases, before-commit guards and CAS. Low-level driver probes and TypeHandlers SHALL be explicitly allowlisted.

#### Scenario: Transaction fails during task acceptance
- **WHEN** a mapped statement fails after partial acceptance writes
- **THEN** message, task, budget and outbox changes roll back on the same transaction

#### Scenario: Owner expires before commit
- **WHEN** the database-time lease expires before an execution transaction commits
- **THEN** the mutation including SDK state is rejected and no partial write is committed

### Requirement: Framework-managed messaging without ownership changes
Spring AMQP SHALL manage messaging lifecycle while durable claim commit precedes manual ACK and database ownership remains authoritative.

#### Scenario: Confirmation or ACK is lost
- **WHEN** reconnect or redelivery repeats a dispatch
- **THEN** no second owner or model request is created and broker failure does not enable direct database execution

### Requirement: Central HTTP infrastructure with live authorization
Security filters and typed validation SHALL preserve existing session, Origin, CSRF and Problem contracts; use cases SHALL continue checking live space and credential authorization.

#### Scenario: Membership revoked after authentication
- **WHEN** an authenticated caller loses membership before a protected operation
- **THEN** access is denied without disclosing another space's data

### Requirement: Evidence-based AgentScope reuse
The platform SHALL use one SDK loop and evaluate supported framework extensions before custom infrastructure; it SHALL retain canonical ledger, budget and side-effect authority and SHALL NOT enable unapproved builtin capabilities.

#### Scenario: SDK extension cannot preserve recovery semantics
- **WHEN** a public state or compaction extension fails a reproducible compatibility test
- **THEN** the gap and minimal adapter are documented and tested without silently weakening recovery or forking third-party source

### Requirement: Architecture-complete future stage plans
Every unfinished stage SHALL define frameworks, module and adapter responsibilities, data and transaction flow, frontend behavior, legacy capability mapping and observable failure/recovery acceptance before coding.

#### Scenario: Plan contains only a feature list
- **WHEN** an implementation plan lacks framework boundaries or end-to-end persistence and UI behavior
- **THEN** the implementation gate remains closed until those decisions and tests are specified

### Requirement: Honest correction status and promotion
R2.2 SHALL wait for verified correction. Planning validation SHALL NOT count as runtime acceptance, and historical R2.1 evidence SHALL remain distinguished from migration regression.

#### Scenario: Documents pass strict validation
- **WHEN** the correction documents validate but runtime migration has not run
- **THEN** correction implementation remains NOT_STARTED, runtime checks remain NOT_RUN and cloud R1 is unchanged
