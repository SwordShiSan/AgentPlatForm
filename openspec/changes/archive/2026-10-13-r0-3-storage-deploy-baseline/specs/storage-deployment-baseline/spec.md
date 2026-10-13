## ADDED Requirements

### Requirement: Reversible minimal-host maintenance
The platform SHALL preserve referenced legacy data and current/rollback images, snapshot restart policies, clean only proved obsolete resources, and restore legacy availability after failed acceptance.

#### Scenario: Cleanup excludes live data
- **WHEN** cloud maintenance inventories referenced volumes and obsolete application images
- **THEN** only unreferenced obsolete images and bounded historical logs are removed, and referenced volumes and rollback images remain intact

#### Scenario: Docker restart does not reactivate legacy
- **WHEN** gVisor installation restarts Docker inside the acceptance window
- **THEN** paused legacy containers remain stopped and their original policies can be restored

### Requirement: Independent durable storage contracts
The system SHALL use independent least-privilege PostgreSQL schemas, scoped atomic state CAS, Redis cache only, confirmed AMQP delivery and private S3 objects with independent content hashes.

#### Scenario: Persistence failure
- **WHEN** a transaction or state CAS fails or a foreign space accesses state
- **THEN** no partial durable handoff or foreign state is returned and a conflict cannot silently overwrite state

#### Scenario: Duplicate and stale dispatch
- **WHEN** confirm is lost, a message repeats or an old generation/fence is presented
- **THEN** stable event identity is retained, at most one valid handoff commits and ACK follows durable commit

#### Scenario: Object service restarts
- **WHEN** private multipart content is uploaded and SeaweedFS services restart
- **THEN** HEAD/GET/Range retain matching own SHA256 and metadata, without treating ETag as content SHA256

### Requirement: Honest readiness and role isolation
The API SHALL retain shell health, expose minimal READY/NOT_READY readiness with200/503, and keep detailed topology/observations internal. Runner/egress SHALL NOT access business DB; only runner management can access Docker.

#### Scenario: Dependency unavailable
- **WHEN** required storage or messaging is unavailable
- **THEN** readiness reports NOT_READY without exposing hosts or credentials, while liveness is not falsely equated with dependency readiness

### Requirement: Bounded fail-closed sandbox
The platform SHALL limit execution to one on-demand gVisor/systrap sandbox, enforce nonroot/memory/CPU/PID/network/mount constraints, check host memory admission, and destroy idle containers after60seconds without deleting files.

#### Scenario: Runtime missing or budget exhausted
- **WHEN** gVisor is missing or required resources are unavailable
- **THEN** shell is disabled or work is queued, never silently executed through runc or uncontrolled swap

#### Scenario: Exhaustion or idle
- **WHEN** a sandbox exhausts its limits or becomes idle without pending operations
- **THEN** platform services remain alive, unknown effects are not replayed and idle teardown preserves workspace content

### Requirement: Verified filesystem hard quotas
Workspace I/O SHALL require a verified dedicated4GiB XFS image mount with prjquota, UUID match and per-workspace byte/inode enforcement. Missing mounts SHALL NOT redirect writes to the underlay.

#### Scenario: Quota and persistence
- **WHEN** writes exceed workspace byte/inode quota or the filesystem is remounted and sandbox recreated
- **THEN** over-quota writes fail and committed files and quota assignments persist

### Requirement: Responsive honest baseline UI
The Vue shell SHALL use shared tokens, accessible focus/touch targets and responsive error states without fake business capabilities.

#### Scenario: Viewport and connection failure
- **WHEN** viewport375/768/1440, dark theme,200%zoom or offline/forbidden/not-found state is used
- **THEN** primary actions remain accessible, errors are distinct from empty success and reconnect uses the real API

### Requirement: Evidence before promotion
R0.3 SHALL require actual cloud dependency/sandbox/quota/rollback tests,30-minute resource observations and reviewed two-stage PR promotion. Cloud developer acceptance SHALL remain distinct from production release.

#### Scenario: Incomplete mandatory gate
- **WHEN** a mandatory test fails or is not run
- **THEN** R0.3 remains IMPLEMENTING, the change is not archived and no production RELEASED claim is made
