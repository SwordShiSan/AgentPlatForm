## ADDED Requirements
### Requirement: Proven cancellation convergence
The platform SHALL separate cancel acceptance from actual local quiescence and retain ownership until it has a fenced stop receipt.
#### Scenario: Queued or running cancellation races completion
- **WHEN** cancellation and execution race
- **THEN** database lock order decides; queued work never calls the provider, running work stops before terminal convergence, and prior terminal success is unchanged
#### Scenario: Stop cannot be confirmed
- **WHEN** SDK/provider/callback teardown does not finish within5seconds or DB is unavailable
- **THEN** the execution slot is retained, no new call or stale write is allowed, and only valid local quiescence or trusted expired-owner host proof can release ownership
#### Scenario: Cancellation arrives after a known blocked outcome
- **WHEN** a BLOCKED task has a stop receipt matching its latest attempt, no live owner and no unresolved calls
- **THEN** cancel converges once to CANCELLED without calling a provider, replaying a task or waiting for a nonexistent reconciliation item; absent stop proof it preserves BLOCKED
### Requirement: Evidence based unknown accounting
The platform SHALL preserve unknown outcomes and reservations until stopped-only immutable, idempotent and revision-checked reconciliation establishes supported facts.
#### Scenario: Evidence resolves a call
- **WHEN** protected operator evidence confirms non-execution or known bounded usage
- **THEN** accounting changes once, complete audit history remains, unresolved usage is never zero-filled and no started request is automatically replayed
#### Scenario: Evidence is insufficient or conflicting
- **WHEN** owner is not stopped, revision/evidence mismatches, usage exceeds allowance or a settled decision is reversed
- **THEN** reject or preserve unresolved holds without content publication, refund duplication or rescheduling
### Requirement: Authorized cancellation visibility
The platform SHALL expose read-only stop and reconciliation state under live space authorization without internal evidence or credentials.
#### Scenario: User observes cancellation and continues
- **WHEN** stop is proven but a model round remains unknown
- **THEN** partial remains visible, unknown is explained as excluded from future context, and a budget-admitted independent task may start without replaying the old request
#### Scenario: Unauthorized reader or delayed stream
- **WHEN** another user or administrator selects foreign resources, or identity changes
- **THEN** reads/cancels are denied and late projections cannot cross actor/space/conversation generations
