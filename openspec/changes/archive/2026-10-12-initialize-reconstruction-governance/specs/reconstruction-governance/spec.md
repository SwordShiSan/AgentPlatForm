## ADDED Requirements

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
