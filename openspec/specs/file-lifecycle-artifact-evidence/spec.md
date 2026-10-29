# file-lifecycle-artifact-evidence Specification

## Purpose
Provide bounded, version-checked file lifecycle operations and history restoration through the existing durable runner chain, with immutable artifact references and independently reported evidence status.
## Requirements
### Requirement: Bounded persistent file lifecycle
The system SHALL move, delete and restore files and directory roots through durable signed runner jobs with at most1000 frozen entries, version checks and the existing exclusive workspace lease.
#### Scenario: Rename without overwrite
- **WHEN** an authorized user moves a confirmed file to an unused normalized destination
- **THEN** the physical rename and durable projection retain its fileId and immutable content version
#### Scenario: Concurrent target change
- **WHEN** source facts or destination change after intent freezes
- **THEN** the command rejects or retains an unknown result without overwriting or replaying effects
#### Scenario: Bounded directory
- **WHEN** a directory contains more than1000 entries including its root
- **THEN** admission rejects before changing the workspace

### Requirement: Tombstones and immutable restore
The system SHALL preserve deleted logical identities and immutable history while new active files at the same path receive new identities. Restore SHALL create new assets and versions with recorded origins and quota accounting.
#### Scenario: Delete and recreate
- **WHEN** a deleted path is uploaded again
- **THEN** its new fileId is distinct and scanning does not revive the tombstone
#### Scenario: Restore collision
- **WHEN** an authorized user restores history to an occupied destination
- **THEN** the system rejects and retains history without silent overwrite
#### Scenario: Cleanup uncertainty
- **WHEN** helper termination or cleanup cannot be proven
- **THEN** ownership and reservations remain held while the original command is queried

### Requirement: Artifact evidence is persistent fact
The system SHALL bind artifacts to authorized task, successful operation, immutable file version and available asset facts, with explicit evidence status.
#### Scenario: Forged artifact
- **WHEN** result JSON names a plausible but nonexistent or foreign version
- **THEN** no verified artifact is published
#### Scenario: Source file removed
- **WHEN** the working file is moved or deleted
- **THEN** an existing artifact still downloads its original hash under current authorization
#### Scenario: Revoked member
- **WHEN** an actor loses resource permission
- **THEN** version and artifact access are rejected regardless of retained references

