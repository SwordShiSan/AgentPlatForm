## ADDED Requirements
### Requirement: Version-aware text editing
The system SHALL edit only confirmed strict UTF8 files no larger than2MiB and SHALL use metadata revision and fixed baseVersion for saving through the existing upload pipeline.
#### Scenario: Concurrent modification
- **WHEN** another writer changes the base version
- **THEN** the editor preserves local text and offers explicit comparison, merge or save-as without automatic overwrite
#### Scenario: Uncertain save
- **WHEN** a save response is lost
- **THEN** the editor queries the original upload and operation and does not create another write
### Requirement: Bounded private session drafts
The system SHALL isolate session drafts by account/space/workspace/file and SHALL clear them on logout or identity expiry.
#### Scenario: Refresh with draft
- **WHEN** a user refreshes an editing page
- **THEN** the draft is restored after authorization and base-version comparison without automatic saving
#### Scenario: Browser storage unavailable
- **WHEN** the4MiB limit or browser quota prevents persistence
- **THEN** the editor retains the current in-memory buffer and explicitly reports memory-only retention
### Requirement: Responsive framework editor
The system SHALL use local lazy Monaco assets on desktop and a lightweight mobile editor with the same save semantics and lazy paginated virtual tree.
#### Scenario: Mobile edit
- **WHEN** a mobile user edits text
- **THEN** save, dirty state and conflict handling remain available without Monaco
