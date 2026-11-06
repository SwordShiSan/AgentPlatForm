## ADDED Requirements

### Requirement: Resolved configuration is authoritative
The system SHALL resolve permitted platform, personal, workspace and explicit task configuration with field provenance and current constraints.

#### Scenario: Workspace overrides personal preference
- **WHEN** an authorized user previews a READY workspace with an override
- **THEN** the workspace value and its source are shown without allocating an execution environment

#### Scenario: Override is cleared
- **WHEN** the user clears a workspace field with a matching version
- **THEN** it inherits the personal or platform value and its revision advances

#### Scenario: Credential is unavailable
- **WHEN** USER credentials are missing or revoked
- **THEN** preview reports a stable blocker and never falls back to PLATFORM

### Requirement: Task submission freezes the resolved configuration
The system SHALL use one resolution for attachment admission and transactional task acceptance, preserve the original idempotency identity and freeze configuration provenance.

#### Scenario: Explicit false differs from omission
- **WHEN** a personal default enables Plan and a request explicitly disables it
- **THEN** the new task freezes Plan disabled, while an omitted option inherits the default

#### Scenario: Configuration changes before acceptance
- **WHEN** the semantic configuration hash no longer matches the preview
- **THEN** acceptance fails with 412 without creating a task or reservation

#### Scenario: Accepted request is retried after a default changes
- **WHEN** the same authorized idempotent request is retried
- **THEN** the original task is returned and no second task is created

#### Scenario: Authorization is revoked
- **WHEN** a user loses access to the workspace or credential
- **THEN** subsequent access or execution is rejected even if an earlier snapshot exists

### Requirement: Configuration edits use version control
The system SHALL protect personal and workspace updates with current versions, maintain audit in the same transaction and retain unsaved edits on conflict.

#### Scenario: Concurrent edits conflict
- **WHEN** a stale If-Match is submitted
- **THEN** the server returns 412 and the client preserves the unsaved values
