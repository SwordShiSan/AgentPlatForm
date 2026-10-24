## ADDED Requirements
### Requirement: Authorized files independent of sandbox
The platform SHALL authorize every file, upload, operation and asset by live identity and space, and SHALL operate on storage READY without creating an execution environment.
#### Scenario: Idle workspace file access
- **WHEN** a member lists a storage-ready workspace with no sandbox
- **THEN** scoped metadata is returned without allocating a sandbox
#### Scenario: Foreign administrator access
- **WHEN** an administrator requests another user's file or upload
- **THEN** access is denied without exposing object keys or physical paths
### Requirement: Durable uploads and immutable versions
The platform SHALL reserve object bytes atomically and freeze an idempotent upload before streaming at most100MiB to private staging, validating exact length and SHA256. It SHALL commit file versions and usage only after a confirmed filesystem effect and immutable object hash.
#### Scenario: New file completion
- **WHEN** a validated upload completes through confirmed MQ dispatch and a signed runner command
- **THEN** one stable file and immutable version become visible with one usage charge
#### Scenario: Changed idempotent request
- **WHEN** the same key is reused with changed path, content identity or version
- **THEN** the request is rejected without another reservation
#### Scenario: Interrupted content
- **WHEN** streaming ends early or exceeds the declared size
- **THEN** no file version is published and incomplete multipart data is bounded and reconciled
#### Scenario: Cold metadata and asset collection allocation
- **WHEN** the bounded storage server holds filer metadata and accepts a non-empty multipart asset
- **THEN** official volume-growth configuration leaves assignable asset capacity while preserving disk and memory admission; HEAD or an empty object alone does not prove this capability
#### Scenario: Insufficient temporary replacement capacity
- **WHEN** the checked workspace directory has insufficient available blocks or inodes for the new temporary file
- **THEN** the helper rejects before writing, retains the old file and version, and never counts a prospective overwrite as reclaimed capacity
### Requirement: Explicit optimistic replacement
The platform SHALL reject implicit overwrite and SHALL require fileId, baseVersionId and If-Match for explicit replacement under an exclusive workspace lease.
#### Scenario: Concurrent replacement
- **WHEN** two uploads replace the same base version
- **THEN** only one replacement commits and the other returns version conflict preserving the original upload identity
#### Scenario: Missing precondition
- **WHEN** replacement omits If-Match
- **THEN** the response is428 and no effect is dispatched
### Requirement: Query-only uncertain recovery
The platform SHALL persist operation, command and exact helper intent before effects, preserve fixed lock ordering and fence checks, and SHALL NOT repeat an uncertain filesystem effect or refund an unresolved reservation.
#### Scenario: Rename before database failure
- **WHEN** rename succeeds but projection commit fails
- **THEN** recovery reads the original runner receipt and commits at most one version without repeating rename
#### Scenario: Unconfirmed stop
- **WHEN** helper termination cannot be proved
- **THEN** ownership and reservations remain blocked despite lease expiration
### Requirement: Bounded and safe data plane
The platform SHALL use fixed non-root trusted helpers with no network or credentials, descriptor-relative no-follow file access, NFC paths and real XFS mount/quota checks. Bytes SHALL use bounded streams rather than full-file arrays or Base64 JSON.
#### Scenario: Link and special-file rejection
- **WHEN** a path races a link or identifies a nonregular file
- **THEN** the operation fails without crossing the workspace root
#### Scenario: Quota exhaustion
- **WHEN** byte, inode or atomic-replacement temporary capacity is exhausted
- **THEN** the operation reports a stable failure and retains old content and accurate accounting
### Requirement: Immutable downloads and honest projections
The platform SHALL serve authorized immutable versions with ETag and single-range support, and SHALL mark unsealed filesystem changes as pending rather than confirmed current content. Text preview SHALL be strict UTF-8 and at most2MiB.
#### Scenario: Binary and invalid UTF8
- **WHEN** preview encounters binary or invalid UTF8 content
- **THEN** download remains available and unsafe inline rendering is not attempted
### Requirement: Complete local acceptance and protected closure
The stage SHALL pass engineering, real PG/MQ/S3, native XFS/helper faults, browser and at least1800s bounded observation before protected two-level promotion and VERIFIED status.
#### Scenario: Incomplete evidence
- **WHEN** any mandatory gate fails or is unrun
- **THEN** the stage remains implementing and does not deploy R3 or claim VERIFIED

### Requirement: Bounded SDK stdin framing
The production runner SHALL send exact-length payloads with an explicit validated trailer through the existing official Docker SDK. Helper completion SHALL NOT depend on a transport half-close or an assumed EOF.
#### Scenario: Open transport after valid payload
- **WHEN** the declared bytes and matching trailer arrive while the input transport remains open
- **THEN** the helper validates and completes without reading beyond that frame
#### Scenario: Missing or corrupt trailer
- **WHEN** a v2 payload has a truncated or mismatched trailer
- **THEN** the helper rejects before rename and preserves the existing file

### Requirement: Positive-only recovery of staged upload metadata
The worker SHALL reconcile the original object by bounded authorized reads and revision CAS. It SHALL NOT infer absence, stop or refund from elapsed time, repeat content upload, or automatically queue filesystem execution.
#### Scenario: Object completed but upload metadata commit failed
- **WHEN** the original staged object has the exact length and full hash, its transport admission is available and the fenced upload remains current
- **THEN** the same upload becomes VERIFIED without another object write, reservation change or filesystem command
#### Scenario: Missing object or unconfirmed transport
- **WHEN** an object is missing, differs, the transport is still held or close is unconfirmed
- **THEN** metadata and reservation remain pending without replay or release based on expiry
