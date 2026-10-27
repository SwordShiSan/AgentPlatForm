# exact-tool-approval Specification

## Purpose
TBD - created by archiving change r4-3-exact-tool-approval-resume. Update Purpose after archive.
## Requirements
### Requirement: Durable approval waiting
The platform SHALL persist the complete model round, original operation and approval intent before SDK ASK, seal a WAITING_APPROVAL checkpoint retaining ASKING calls, and release ownership only after actual SDK/provider/callback termination is confirmed. Public task status SHALL remain WAITING_INPUT and kind SHALL be TOOL_APPROVAL.
#### Scenario: Stop and restart before approval
- **WHEN** an ASK run exits and another JVM resumes after a valid answer
- **THEN** the same task and original operation are restored without a replacement model call or duplicate operation.
#### Scenario: Stop cannot be confirmed
- **WHEN** transport closure or lease expiry occurs without complete termination evidence
- **THEN** the task is BLOCKED and the slot is not released.

### Requirement: Exact single-use authorization
The platform SHALL bind approval to actor/space/task/interaction/operation/call hash/resource versions/policy/permission revision/source checkpoint. Answers SHALL not mutate canonical operations before checkpoint verification. ALLOW_ONCE consumption and PREPARED to RUNNING SHALL be atomic in the owned transaction and SHALL recheck live authorization/fence/lease.
#### Scenario: Repeated approval and delivery
- **WHEN** duplicate answers, confirm/ACK loss or multiple workers race
- **THEN** one effective answer/resume/consumption exists and the deterministic side effect occurs at most once.
#### Scenario: Stale or unauthorized approval
- **WHEN** target, policy, permission revision, actor authorization or fence no longer matches
- **THEN** approval cannot admit execution; FULL and admin do not bypass hard restrictions.

### Requirement: Durable denial and unknown handling
The platform SHALL commit zero-effect DENIED observations after restoring the checkpoint, include them in canonical history/checkpoints and suppress repeated identical denied fingerprints within the task. UNKNOWN or RUNNING with uncertain effects SHALL NOT be replayed. Successful results SHALL be read back without execution; missing safe checkpoints SHALL block.
#### Scenario: User denies
- **WHEN** DENY is accepted and restored
- **THEN** the original call receives a structured denied result, no side effect occurs and text can continue without repeated approval prompts for the same fingerprint.
#### Scenario: Crash after physical operation
- **WHEN** no trustworthy committed outcome exists
- **THEN** the operation remains UNKNOWN with its reservations and no automatic re-execution.

### Requirement: Compatible typed interaction API
The platform SHALL extend the existing answer endpoint and snapshot/events with TOOL_APPROVAL and ALLOW_ONCE/DENY, preserve Plan/Question semantics, reject changed idempotent bodies with409, stale interaction versions with412, and opposite resolved decisions with409. Approval expires after24hours and SHALL NOT grant directory scope.
#### Scenario: Lost response and multiple tabs
- **WHEN** the same decision is resubmitted after response loss
- **THEN** the original acceptance is returned; an opposite decision cannot replace it.
#### Scenario: Stale target review
- **WHEN** the binding becomes stale before approval
- **THEN** ALLOW_ONCE is rejected with412 and the user may deny or cancel; DENY does not require the target version to become valid again.

### Requirement: Framework and scope boundaries
The platform SHALL reuse AgentScope DEFAULT, exact ConfirmResult with empty rules and one Harness. Complete batches with more than one BUSINESS or mixed CONTROL/BUSINESS SHALL be rejected before tool execution with TOOL_BATCH_UNSUPPORTED. Production business/shell/network tools SHALL remain unavailable.
#### Scenario: Unsupported batch
- **WHEN** a model returns multiple BUSINESS calls or a mixed batch
- **THEN** the complete list is retained, no call executes and the batch is not truncated.

### Requirement: Budget and resource continuity
Waiting/resume SHALL preserve cumulative active120seconds, task budgets and user daily accounting. The configurable ordinary-user default SHALL remain1000000tokens/day; administrators SHALL be metered without quota admission limits. Supplier calls SHALL be zero for this step.
#### Scenario: Resume after wait
- **WHEN** approval resumes a task
- **THEN** existing consumed budgets and UNKNOWN holds remain attached and no request-count ceiling is reintroduced.

