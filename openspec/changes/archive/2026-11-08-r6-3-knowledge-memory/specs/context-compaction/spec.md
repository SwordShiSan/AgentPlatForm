## ADDED Requirements

### Requirement: Context evidence carries provenance and current authorization
The system SHALL represent memory and retrieval candidates with source identity, version, hash, location, required status, token estimate and degradation state and SHALL revalidate access before provider admission.

#### Scenario: Optional candidate service degrades
- **WHEN** an optional recall or search dependency is unavailable
- **THEN** the provider input may omit that candidate and the task records a stable degradation reason

#### Scenario: Required evidence is revoked
- **WHEN** required source access or version validity changes before the next model request
- **THEN** the request is blocked before provider execution and the canonical ledger remains unchanged

### Requirement: Context reduction is a derived versioned projection
The system SHALL create deterministic extractive context projections keyed by task, runtime bundle, ledger watermark, context epoch and source manifest hash without rewriting canonical conversation history or making a separate summary-model request.

#### Scenario: Safe-point extractive projection succeeds
- **WHEN** the effective input exceeds 80 percent of its task window and old assistant-only prose can be reduced
- **THEN** the platform may deduplicate repeated assistant-only text and replace eligible old assistant prose with a bounded, explicitly untrusted historical excerpt; it preserves USER/SYSTEM messages, source references, approvals, complete tool calls and observations, and commits only a smaller in-budget projection by CAS

#### Scenario: Projection cannot safely fit
- **WHEN** required input alone exceeds the task limit, or the extractive projection does not reduce the context enough
- **THEN** the old projection remains active, canonical history is unchanged, and the task returns a stable size error without truncating required content

#### Scenario: Projection adds no provider call
- **WHEN** deterministic projection is evaluated or committed
- **THEN** no additional model request or provider usage is created

#### Scenario: Restore uses the committed projection
- **WHEN** the same task is restored from an accepted checkpoint
- **THEN** it verifies bundle, watermark, epoch, source manifest and projection hash before using the summary

#### Scenario: Legacy bundle is restored
- **WHEN** a task uses a pre-R6.3 bundle with no projection metadata
- **THEN** the platform preserves its prior context behavior and does not apply the new compaction path

### Requirement: Context summary is read-only and redacted
The system SHALL expose bounded context estimates and lineage metadata only to an authorized task owner.

#### Scenario: Owner inspects context status
- **WHEN** the task owner requests context-summary
- **THEN** the response contains estimate, limits, watermarks, compression revision and stable degradation codes without prompt or secret contents

#### Scenario: Unauthorized user inspects context
- **WHEN** another user or administrator without ownership requests the same summary
- **THEN** the API denies access and reveals no source or summary text
