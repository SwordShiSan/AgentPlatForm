# workspace-control Specification

## Purpose
Define durable, scoped storage-only workspace provisioning, authenticated runner commands/results and quota reconciliation without exposing execution environments or file/shell capabilities.
## Requirements
### Requirement: Durable scoped workspace provisioning
The platform SHALL atomically persist scoped workspace, capacity reservation, operation and outbox before dispatch, with stable idempotent identities and live authorization.
#### Scenario: Concurrent identical submission
- **WHEN** two authorized requests use the same key and body
- **THEN** exactly one workspace, operation and2GiB reservation exist and both resolve that identity
#### Scenario: Failed transaction or capacity admission
- **WHEN** any required write fails or physical capacity is already reserved
- **THEN** no partial workspace/outbox/reservation is committed and no runner effect is started
#### Scenario: Foreign or revoked actor
- **WHEN** a foreign user, administrator without membership or revoked member reads or mutates the workspace
- **THEN** access is rejected without exposing resource metadata

### Requirement: Reliable authenticated lifecycle commands
The platform SHALL publish only committed outbox work, ACK only committed claims, and use authenticated signed internal HTTP with lease/generation/fence and durable replay protection.
#### Scenario: Lost delivery or response
- **WHEN** confirm, ACK or runner response is lost
- **THEN** repeated delivery resolves the original operation and no second physical allocation occurs
#### Scenario: Invalid signature or stale owner
- **WHEN** algorithm, issuer, audience, time, method, path, body, nonce or fence validation fails
- **THEN** no filesystem/Docker effect or successful receipt is accepted
#### Scenario: Unknown effect
- **WHEN** the previous result or owner termination is not known
- **THEN** the workspace is BLOCKED with reservation retained and no automatic effect replay or blind retry
#### Scenario: Expired owner without a response
- **WHEN** an active lifecycle lease expires before a committed control-plane result
- **THEN** a database-time guarded sweep marks UNKNOWN/BLOCKED while retaining the original owner, fence and reservation and never starting a replacement effect

### Requirement: Real bounded storage without user execution
The runner SHALL resolve only controlled UUID paths on verified XFS prjquota and provision at most one2GiB/100000inode workspace in the initial4GiB filesystem.
#### Scenario: Missing mount or unsafe path
- **WHEN** the expected mount, UUID, quota or path ownership is invalid
- **THEN** provisioning refuses before writing a fallback directory
#### Scenario: Ready storage
- **WHEN** actual directory/project/quota identity is verified
- **THEN** workspace storage is READY but environment remains NOT_CREATED and shell/file capabilities remain unavailable
#### Scenario: Native quota device identity
- **WHEN** the helper validates a real XFS mount
- **THEN** its source must be the operator-pinned non-symlink loop block node matching filesystem device identity, exposed to this trusted helper only with read device permission, or provisioning refuses before effects without widening capabilities or sandbox access
#### Scenario: Repeated or interrupted allocation
- **WHEN** a matching allocation exists or a previous allocation was interrupted
- **THEN** durable facts are reconciled without deleting unknown or foreign resources

### Requirement: User-visible control and safe stage delivery
The platform SHALL expose authorized create/list/detail/explicit-retry views and verify real dependencies, browser, Linux resource and restore boundaries before marking the stage VERIFIED.
#### Scenario: Browser refresh and uncertain result
- **WHEN** the user refreshes after submission or observes UNKNOWN
- **THEN** the same workspace/status is recovered and no automatic retry or future execution controls are shown
#### Scenario: Cloud acceptance window
- **WHEN** Linux-only gates require the cloud host
- **THEN** independent test resources run serially with production stopped and the same released R2 is restored after cleanup
