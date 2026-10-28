# workspace-business-tools Specification

## Purpose
Specify task-bound production file and shell tools, Plan and original-operation approval, owner-fenced durable handoff, confirmed termination, immutable output versions and bounded public results on the local gVisor/XFS/S3 architecture.
## Requirements
### Requirement: Versioned workspace business tools
The platform SHALL expose typed list/read/write/general-shell tools only to new tasks with a frozen authorized workspace and catalog. It SHALL reuse the single AgentScope Harness, existing durable ledger and framework adapters, reject incomplete/oversized/mixed business batches before effects, and include actual schemas in context accounting.
#### Scenario: Unbound or malformed invocation
- **WHEN** no workspace is bound or a call exceeds64KiB aggregate arguments or is truncated
- **THEN** no physical operation starts and no default host workspace is selected.

### Requirement: Plan and mode authorization
An approved current Plan revision SHALL permit same-task execution subject to live resource and operation authorization. RISK_BASED SHALL ask for every shell command; FULL MAY auto-admit within the same workspace but SHALL NOT bypass hard policies. Directory file grants SHALL NOT grant shell authority. Historical bundles SHALL retain their original capabilities.
#### Scenario: Current Plan approved
- **WHEN** EXECUTE_PLAN resumes the exact current revision
- **THEN** business tools become eligible without granting resources or skipping required operation approval.
#### Scenario: Permission or target changed
- **WHEN** revision, content, identity, policy or fence is stale at admission
- **THEN** the original operation cannot execute even in FULL mode.

### Requirement: Durable activation and execution identity
The platform SHALL bind a stable tool intent to one activation/file/use job through owned transactions and durable MQ handoff. It SHALL distinguish task owner fences from environment generations, preserve approval hashes across its own expected activation, and query uncertain original commands without replay.
#### Scenario: Automatic wake after approval
- **WHEN** a valid admitted shell requires a stopped environment
- **THEN** only the original activation prepares a new generation and physical identity is bound once without invalidating the stable approval.
#### Scenario: Lost response or duplicate message
- **WHEN** a command may already have executed
- **THEN** the same command is queried and a second physical effect is not issued.

### Requirement: Actual shell termination
General shell SHALL run noninteractively inside the fixed networkless gVisor sandbox and MAY alter its entire bound workspace. Every call SHALL stop the entire exact sandbox and confirm exec/container/callback termination before releasing capacity or sealing outputs. Transport/Future cancellation and lease expiry SHALL NOT establish stopping proof.
#### Scenario: Background child survives parent command
- **WHEN** the shell parent exits while descendants remain
- **THEN** the sandbox is stopped and descendant termination is confirmed before output sealing.
#### Scenario: Stop remains uncertain
- **WHEN** trusted termination evidence is missing
- **THEN** the operation is BLOCKED/UNKNOWN and required leases/reservations remain held.

### Requirement: Immutable outputs and truthful tool results
The platform SHALL capture at most16 declared regular outputs totaling at most100MiB through bounded helper/S3 transfer with quota reservations, hashes and immutable versions. It SHALL classify known failures as FAILED and preserve UNKNOWN without replay. Unsealed changes SHALL NOT be advertised as current confirmed versions.
#### Scenario: Command succeeded and output sealed
- **WHEN** actual stop and declared output hashes are confirmed
- **THEN** one version/reference and one quota settlement become visible before the SDK receives success.
#### Scenario: Nonzero exit or missing declared output
- **WHEN** the physical outcome is known to be unsuccessful
- **THEN** the ledger and SDK receive a known failure and any correction requires a new independent call.

### Requirement: Safe public projections and release
Operations and bounded results SHALL be exposed only through current task/space authorization and persistent ToolCard events, without private credentials or SDK state. R4 release SHALL require accepted immutable local artifacts, protected promotion, production backup, functional observation, isolated restore and R3 rollback/restart; deferred cloud sandbox changes SHALL remain unmerged.
#### Scenario: Cross-user request
- **WHEN** another user or an administrator requests an unowned operation/result
- **THEN** no content is disclosed and admin quota exemptions do not bypass access controls.
#### Scenario: Deployment failure
- **WHEN** any required production gate fails
- **THEN** the retained exact R3 deployment is restored and R4 is not marked RELEASED.
