# plan-question-interactions Specification

## Purpose
Specify durable Plan review and clarification, versioned text or workspace execution eligibility, exact same-task resumption, and configurable per-user daily quota accounting. Confirmation itself grants no resource or business tool permissions.
## Requirements
### Requirement: Durable typed control tools
The platform SHALL use one AgentScope Harness and typed CONTROL tools for plan writing, review and questions; it SHALL commit a complete model round before a control intent, allow at most one control call per round, and SHALL NOT grant business tool permissions.

#### Scenario: Structured control request
- **WHEN** a complete valid plan_write tool call is received
- **THEN** its immutable plan revision is persisted before any review is opened

#### Scenario: Invalid or parallel control requests
- **WHEN** nested arguments are invalid or a round contains two CONTROL calls
- **THEN** the batch is rejected without opening a stale review or executing business tools

### Requirement: Stopped waiting and exact restoration
The platform SHALL seal a WAITING_INPUT checkpoint only after normal ASK, record confirmed termination before releasing ownership, and restore the same task in a new attempt through outbox and RabbitMQ using exact ConfirmResult and a separate answer watermark.

#### Scenario: Cross process answer
- **WHEN** a waiting attempt has a committed checkpoint and stop receipt and its JVM exits
- **THEN** one accepted answer resumes a new attempt using the original call and unchanged checkpoint

#### Scenario: Uncertain stop or state
- **WHEN** stopping cannot be confirmed or checkpoint integrity or bundle validation fails
- **THEN** the task remains blocked and no unknown request is replayed

#### Scenario: Answer race
- **WHEN** two tabs answer the same interaction version
- **THEN** only one answer and one outbox transition commit; stale differing answers fail

### Requirement: Versioned plans and scoped answers
The platform SHALL preserve immutable plan revisions and support execution, revision, rejection and plan-only completion, typed questions, expiry, authorization and UTF-8 limits.

#### Scenario: Manual edit and stale confirmation
- **WHEN** a review is pending and the plan is edited with its ETag
- **THEN** a new revision and review identity replace the old review and its old confirmation returns 412

#### Scenario: Plan only
- **WHEN** the user chooses DELIVER_PLAN
- **THEN** the plan is saved and the task succeeds without another physical model request

#### Scenario: Question expiry and optional input
- **WHEN** a required question expires or an optional answer arrives after task completion
- **THEN** expiry never approves execution and the late answer cannot revive the task

#### Scenario: Size and authorization
- **WHEN** a plan exceeds128KiB, an answer exceeds32KiB or current membership is revoked
- **THEN** the request is rejected without truncation or cross-space disclosure

### Requirement: Conserved token cost and active time budgets
Production execution SHALL NOT enforce model request count limits. It SHALL enforce a configurable per-user daily token quota (default1,000,000, Asia/Shanghai calendar day), atomically reserve each physical request, settle known usage and retain UNKNOWN holds across attempts and windows. Platform administrators SHALL be quota-unlimited but metered; account names SHALL NOT confer exemptions. Default cost metering SHALL NOT add a blocking fee limit. It SHALL enforce120seconds cumulative active time excluding user wait.

#### Scenario: More than four requests and eight rounds
- **WHEN** small known usage remains within the daily quota and activity time
- **THEN** execution may exceed4physical requests and8SDK rounds without artificial count rejection

#### Scenario: Settlement and wait
- **WHEN** known usage settles or an answer resumes after a long user wait
- **THEN** unused reservation returns once and consumed budgets and active milliseconds are not reset

#### Scenario: Unknown and exhaustion
- **WHEN** usage is unknown or remaining token or cost cannot reserve another request
- **THEN** unknown holds remain and exhausted admission is rejected without replay or zero usage fabrication

### Requirement: Typed interaction delivery and local verification
The platform SHALL expose authenticated versioned plan and answer APIs, typed snapshot and SSE projections and accessible Vue cards. Component verification SHALL use isolated local or native projects and a protected acceptance-only maximum20 real physical requests. Production deployment SHALL require a separately approved major-phase release gate; local component success alone SHALL NOT authorize release.

#### Scenario: Refresh and lost response
- **WHEN** a browser refreshes or loses an answer response
- **THEN** it queries the original interaction and does not resubmit with a new identity

#### Scenario: Historical text only confirmation
- **WHEN** a historical text-only bundle or an unbound task confirms a plan
- **THEN** only text continuation becomes available and no shell file or workspace tool is granted

#### Scenario: Versioned workspace plan confirmation
- **WHEN** a task with a frozen enabled workspace catalog confirms its current plan revision
- **THEN** the same task becomes eligible for its existing capabilities subject to live resource authorization and original-operation approval; confirmation grants no resource rights and does not approve a shell command

#### Scenario: Subsequent tool approval restores prior control history
- **WHEN** a business approval resumes after an earlier plan confirmation in the same task
- **THEN** the runtime retains typed definitions needed to decode committed historical controls while the provider catalog continues to exclude plan write and review in the execution phase

#### Scenario: Acceptance budget
- **WHEN** the protected real-call counter reaches20 or the provider reports exhausted quota
- **THEN** real acceptance stops and historical usage is preserved

### Requirement: Common configurable quota and visibility
The platform SHALL resolve versioned USER token/cost quota policies, default to a daily1,000,000token limit with no blocking cost limit, use database time and preserve immutable call reservation windows. It SHALL expose used, reserved, remaining, resetAt and unlimited status, and SHALL enforce current role and authorization before each physical request.

#### Scenario: Configurable user quota and administrator exemption
- **WHEN** an ordinary user has no override or a platform administrator submits a request
- **THEN** the ordinary user receives the default daily quota and the administrator is unlimited but all known and UNKNOWN usage remains recorded

#### Scenario: Midnight and concurrent reservations
- **WHEN** two requests compete for remaining tokens or usage arrives after its original daily window ends
- **THEN** reservations cannot oversell, settlement remains in the original window and unresolved holds are not erased by the daily reset

#### Scenario: Cost and role changes
- **WHEN** no cost policy is configured or administrator status is removed before the next request
- **THEN** cost remains metered without a fee cap and the next request uses the current limited user policy

