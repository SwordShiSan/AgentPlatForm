## MODIFIED Requirements
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
