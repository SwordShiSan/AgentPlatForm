# framework-runtime Specification

## Purpose
TBD - created by archiving change r0-2-agentscope-contract-probes. Update Purpose after archive.
## Requirements
### Requirement: Versioned pure composition
The platform SHALL compose HC-1 ports at build time, isolate SDK types in infrastructure, and pin one compatible bundle per run.

#### Scenario: Substitute components
- **WHEN** Context, Memory or Retrieval is replaced by another deterministic fake implementation
- **THEN** LoopDriver code and public contracts remain unchanged and outputs remain scope-bound

#### Scenario: Invalid composition or bundle
- **WHEN** duplicate compaction/memory authorities, unknown implementations, old state schemas or incompatible provider state are supplied
- **THEN** assembly or restoration fails without a model call or empty-state fallback

### Requirement: Commit before effect
The runtime SHALL await persistence of the entire valid model round and complete call list before any tool, and await result persistence before continuing.

#### Scenario: Two calls and a failing commit
- **WHEN** a model requests two calls and round persistence is delayed or fails
- **THEN** neither call executes until the complete list commits and a failure executes neither call

#### Scenario: Invalid or truncated model output
- **WHEN** output is length/abort truncated, empty, malformed or contains an unknown block
- **THEN** no tool executes and a structured failure is reported

### Requirement: Guarded decisions and control
Both permission checking and execution SHALL use the same platform policy; Plan, cancellation, withdrawal and precise approval cannot be bypassed by SDK defaults.

#### Scenario: Approval and control
- **WHEN** a pending approval is restored, denied, changed, duplicated, revoked or supplied in Plan mode
- **THEN** only the original authorized call may execute once and control frames never become platform USER messages

### Requirement: Ledger-first restoration
Recovery SHALL preserve original operation identities, verify complete manifests and use versioned scope-bound SDK state.

#### Scenario: Cross-process operation recovery
- **WHEN** a new JVM restores SUCCEEDED, PREPARED or UNKNOWN operations after a crash
- **THEN** success is reused, prepared calls resume unchanged, unknown effects block and no new inference skips pending operations

#### Scenario: CAS and tenant isolation
- **WHEN** two writers compete for the same expected version or identical user/session identifiers occur in different spaces
- **THEN** one CAS writer succeeds, conflict is explicit, and no state/list/delete crosses the bound scope

### Requirement: Evidence before completion
SDK termination SHALL produce a candidate evaluated by CompletionPolicy rather than platform success by itself.

#### Scenario: Missing evidence
- **WHEN** the SDK finishes but required operation/artifact evidence is missing
- **THEN** the result is REPAIR or INCOMPLETE, never successful and never an automatic second outer Harness loop

### Requirement: Real framework acceptance
R0.2 SHALL require actual SDK, process-recovery and offline Linux Docker probes plus engineering/UI regression before promotion.

#### Scenario: Unmet mandatory case
- **WHEN** any required case fails or cannot run
- **THEN** R0.2 remains IMPLEMENTING, the failure is retained, and no dependent stage or main promotion occurs

### Requirement: Explicit text bundle and real state compatibility
The platform SHALL admit only the historical scripted bundle or the frozen DeepSeek text-stream bundle, persist its actual manifest hash, and use exact nonnegative CAS versions for SDK private state in PostgreSQL.
#### Scenario: Real database private state save
- **WHEN** Harness saves unversioned private state during a text attempt
- **THEN** the adapter reads its current slot version and performs exact CAS without a negative sentinel or blind overwrite
#### Scenario: Unknown bundle or stale state version
- **WHEN** an unregistered model/component combination or stale CAS version is supplied
- **THEN** composition or state writing fails, and the historical scripted bundle remains compatible

### Requirement: Streaming model port compatibility
The framework adapter SHALL support live asynchronous text deltas and downstream cancellation without exposing SDK types in domain ports. Existing complete-only probes SHALL remain compatible; production streaming SHALL NOT collect all SDK events before completion.
#### Scenario: Slow model stream and cancellation
- **WHEN** the model emits text before completion or is cancelled
- **THEN** deltas reach the platform while running and cancellation closes owned resources without emitting hidden reasoning
