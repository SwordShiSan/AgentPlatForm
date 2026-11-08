# personal-memory Specification

## Purpose
TBD - created by archiving change r6-3-knowledge-memory. Update Purpose after archive.
## Requirements
### Requirement: Memory candidates require explicit confirmation
The system SHALL keep user-created memory candidates pending until the owning user confirms them.

#### Scenario: Candidate is confirmed
- **WHEN** the authorized user confirms a pending candidate using its current revision
- **THEN** the candidate becomes active in the authorized scope and an audit hash is recorded in the same transaction

#### Scenario: Candidate is rejected or edited concurrently
- **WHEN** a candidate is rejected or its revision changed after a stale confirmation
- **THEN** rejection is persisted idempotently or stale confirmation returns a version conflict without activating the old content

#### Scenario: Concurrent memories share a topic
- **WHEN** two transactions attempt to activate the same scope/subject/topic
- **THEN** the database uniqueness invariant allows at most one active version and preserves both audit outcomes

### Requirement: Memory recall respects ownership and expiry
The system SHALL only recall active, unexpired memories authorized for the current user and private workspace.

For a task with a frozen workspace binding, the context adapter SHALL query personal memories and memories scoped to that bound workspace. Unbound and legacy tasks SHALL query personal memories only. Source revalidation before model admission SHALL use the same bound workspace scope.

#### Scenario: Memory expires
- **WHEN** database time reaches the expiry boundary
- **THEN** the memory stops being recalled and preference/procedure entries require review before reuse

#### Scenario: Memory is revoked or source access changes
- **WHEN** an owner deletes a memory or loses access to its source
- **THEN** later model admissions exclude it even if derived context has been cached

#### Scenario: Administrator queries another user's memory
- **WHEN** a governance administrator requests memory content without owner membership
- **THEN** the platform denies access and returns no content metadata

#### Scenario: Workspace memory follows the task binding
- **WHEN** a task is bound to a workspace and active memories exist in personal and that workspace scope
- **THEN** the task context may include both scopes, revalidates both against live authorization, and does not include memories from another workspace

#### Scenario: Unbound legacy task recalls no workspace memory
- **WHEN** an unbound task or historical task has no frozen workspace binding
- **THEN** only active personal memories are eligible for recall
