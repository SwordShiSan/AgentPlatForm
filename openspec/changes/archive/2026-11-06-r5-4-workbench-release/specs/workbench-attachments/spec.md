## ADDED Requirements

### Requirement: Immutable authorized chat attachments
The platform SHALL support private actor-scoped standalone uploads and explicit references to authorized immutable file versions without creating a workspace.

#### Scenario: First submission binds original bytes
- **WHEN** an authorized user submits an extracted attachment with a new conversation
- **THEN** the conversation, task, ordered references, budget and outbox commit atomically and duplicate submissions return the same task.

#### Scenario: Invalid or revoked source
- **WHEN** an attachment is not extracted, is corrupted or access is revoked
- **THEN** the platform rejects its use without dropping the attachment or making a model call.

### Requirement: Bounded frozen context and accounting
The platform SHALL enforce the frozen task and model windows, retain independent daily quota, and reserve each call from its actual estimated input and output allowance.

#### Scenario: Context exceeds effective limit
- **WHEN** complete required input exceeds the frozen usable context
- **THEN** execution is rejected with retained draft and source, without silent truncation or summarization.

#### Scenario: Unknown call and legacy recovery
- **WHEN** a physical call result is unknown or an older task resumes
- **THEN** the original reservation and policy remain authoritative, without automatic replay or rewriting old snapshots.

### Requirement: Isolation and durable processing
The platform SHALL run extraction and previews as fenced persistent jobs using the same heavy capacity as the business sandbox.

#### Scenario: Stop cannot be confirmed
- **WHEN** renderer termination or result is uncertain
- **THEN** the original work remains BLOCKED with its slot and reservation until reconciled, and cannot be blindly repeated.

### Requirement: Workbench and data-safe release
The platform SHALL provide responsive version-pinned workbench and storage projections and release only after independent acceptance and a recoverable maintenance window.

#### Scenario: Version changes after selection
- **WHEN** a selected file changes or moves
- **THEN** its existing attachment and artifact still identify original bytes and stale verification is visibly distinguished.

#### Scenario: R5 release fails before writes reopen
- **WHEN** the candidate fails in maintenance
- **THEN** restore the matching R4 snapshot and original runtime without discarding any real user writes.
