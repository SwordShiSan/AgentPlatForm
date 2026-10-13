## ADDED Requirements

### Requirement: Reproducible engineering
The platform SHALL build a six-module Java 21 backend and a pinned Vue application from committed wrappers and lockfiles, enforcing the canonical dependency DAG.

#### Scenario: Clean build
- **WHEN** the declared verify, typecheck, unit-test and build commands run from their project directories
- **THEN** the application shells build and domain imports no framework, SDK or persistence implementation

### Requirement: Single machine contract
The platform SHALL use OpenAPI 3.1 and event JSON Schema as the machine contract sources and generate frontend client/types without manually maintained divergent DTOs.

#### Scenario: Regeneration
- **WHEN** schema validation and client/type generation run twice
- **THEN** both runs produce identical generated output and drift from committed output fails the check

#### Scenario: Invalid event fixture
- **WHEN** an event has an unknown type, missing required identity, wrong schema version or non-positive seq
- **THEN** event schema validation rejects it

### Requirement: Safe request boundary
The request adapter SHALL apply cookie and explicit request-context headers, surface structured Problem failures, retry GET network errors at most twice and never transparently retry writes or replace an idempotency key.

#### Scenario: Failure and write retry
- **WHEN** requests receive network failure, session expiry or an application/problem+json response
- **THEN** GET retries remain bounded, a write is attempted once, and the caller receives the stable failure information without secret or stack disclosure

### Requirement: Honest health shell
The platform SHALL expose only implemented shell health through the real API and SHALL represent loading, unavailable and healthy states honestly in Vue.

#### Scenario: API unavailable and restart
- **WHEN** fixed Docker artifacts run, the API becomes unavailable and then restarts
- **THEN** the page reports UP only after a successful health response, shows an actionable unavailable state during failure and recovers after a retry

### Requirement: Traceable acceptance
The workflow SHALL require a validated OpenSpec change, real local Docker/UI checks, protected-main CI gates and two PR promotions before marking R0.1 VERIFIED.

#### Scenario: Incomplete acceptance
- **WHEN** Docker, protection, mandatory tests or PR promotion is missing
- **THEN** the step remains IMPLEMENTING with explicit NOT_RUN or blocked evidence and no dependent stage begins
