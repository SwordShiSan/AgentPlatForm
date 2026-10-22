## MODIFIED Requirements

### Requirement: Real bounded storage without user execution
The runner SHALL resolve only controlled UUID paths on verified XFS prjquota and provision at most one2GiB/100000inode workspace in the initial4GiB filesystem.
#### Scenario: Missing mount or unsafe path
- **WHEN** the expected mount, UUID, quota or path ownership is invalid
- **THEN** provisioning refuses before writing a fallback directory
#### Scenario: Initial storage provisioning
- **WHEN** actual directory/project/quota identity is verified
- **THEN** initial workspace storage is READY and environment remains NOT_CREATED until an independently authorized environment preparation operation; public shell/file capabilities remain unavailable
#### Scenario: Native quota device identity
- **WHEN** the helper validates a real XFS mount
- **THEN** its source must be the operator-pinned non-symlink loop block node matching filesystem device identity, exposed to this trusted helper only with read device permission, or provisioning refuses before effects without widening capabilities or sandbox access
#### Scenario: Repeated or interrupted allocation
- **WHEN** a matching allocation exists or a previous allocation was interrupted
- **THEN** durable facts are reconciled without deleting unknown or foreign resources

