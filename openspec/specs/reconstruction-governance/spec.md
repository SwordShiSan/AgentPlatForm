# reconstruction-governance Specification

## Purpose
Define documentation-only reconstruction governance: one execution progress authority, traceable module work, evidence-based stage gates and accurate repository delivery reporting. This specification does not claim any runtime platform feature is implemented.
## Requirements
### Requirement: Single execution progress authority
The reconstruction workflow SHALL maintain execution status only in canonical stage ledgers, with global rollups derived from them.

#### Scenario: Initialization without implementation
- **WHEN** the documentation and repository skeleton are prepared
- **THEN** all forty implementation steps remain NOT_STARTED and business tests remain NOT_RUN

### Requirement: Traceable module implementation
Each functional module SHALL define scoped implementation actions, failure boundaries, UI feedback and acceptance assertions mapped to existing stages.

#### Scenario: Work is claimed
- **WHEN** an engineer selects a function card
- **THEN** its design, stage gate and local progress ledger are reachable without a second technical design body

### Requirement: Evidence before advancement
The workflow SHALL require stage evidence before marking VERIFIED or starting its dependent step.

#### Scenario: Required environment is missing
- **WHEN** a required real-dependency test cannot run
- **THEN** its result is NOT_RUN with a blocking reason, not PASS or an invented verification

### Requirement: Honest repository delivery
Repository delivery SHALL distinguish committed repository files from external canonical documents and local skill configuration.

#### Scenario: Skeleton pushed
- **WHEN** main is pushed to the authorized GitHub repository
- **THEN** the remote commit is verified and the report states that external design bodies are not part of that push

### Requirement: Explicit middleware and ownership design
The reconstruction documentation SHALL compare middleware by capability and define message delivery separately from authoritative task ownership, including durable handoff, retries and failure recovery.

#### Scenario: Messaging selection is revised
- **WHEN** the design introduces a broker
- **THEN** prior contradictory database-queue statements are revised and crash-window acceptance tests are mapped to existing stages without claiming runtime verification

### Requirement: Cloud user asset design
The reconstruction documentation SHALL define personal and organizational asset ownership, authoritative storage, minimal sandbox visibility, lifecycle, quotas and user-facing management interactions.

#### Scenario: User directory convention is considered
- **WHEN** local agent directory practices are referenced
- **THEN** the design distinguishes public vendor facts from the platform proposal and does not treat a directory prefix as a sufficient tenant security boundary

#### Scenario: Design is delivered
- **WHEN** middleware and asset documents pass static checks
- **THEN** runtime stages remain NOT_STARTED and the report distinguishes local canonical documents from repository delivery

### Requirement: Small-stage stable promotion and major-stage production release
The workflow SHALL keep `main` at the latest locally Docker-verified small-stage commit and SHALL treat major-stage production release as a separate gate tracked by an immutable production tag and image digest.

#### Scenario: A small stage passes local acceptance
- **WHEN** its Docker-backed frontend, backend, required dependencies, UI and regression gates pass
- **THEN** the step is reviewed and merged into its phase branch, the phase snapshot is reviewed and merged into main, and main is synchronized back into the phase branch before the next small stage starts

#### Scenario: A major stage passes cloud acceptance
- **WHEN** all small stages are verified and the exact locally canaried image digest passes cloud tests and observation
- **THEN** the major stage is marked RELEASED and receives an immutable production tag without delaying or replacing the per-small-stage main promotions

#### Scenario: Cloud release validation fails
- **WHEN** the candidate image fails online validation
- **THEN** production is rolled back to its previous production tag while main retains the latest locally verified small stage; a fix uses a new step branch and must pass local gates and PR promotion before release validation is retried
