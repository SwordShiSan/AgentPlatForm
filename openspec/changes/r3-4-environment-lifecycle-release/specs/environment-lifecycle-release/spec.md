## ADDED Requirements
### Requirement: Durable authorized lifecycle
The platform SHALL accept versioned idempotent sleep, wake and environment-only deletion through the existing persistent queue chain, with current identity and space authorization.
#### Scenario: Sleep and wake
- **WHEN** an authorized user sleeps a ready environment and later explicitly wakes it
- **THEN** sleep confirms stopping/removal, wake creates one new generation and READY requires real mount/canary evidence
#### Scenario: Duplicate or stale request
- **WHEN** an original request is repeated, its content changes or its version is stale
- **THEN** the same request returns the original operation, changed content is rejected409 and stale versions412 without another physical command
#### Scenario: Foreign administrator
- **WHEN** an administrator targets another user's environment
- **THEN** access is denied without container or path disclosure
### Requirement: Environment deletion preserves user storage
Deleting an environment SHALL preserve workspace identity, directory, project quota, file versions, object references and their accounting.
#### Scenario: Delete and recreate
- **WHEN** deletion completes and the user prepares again
- **THEN** a new environment generation reads the same current files and immutable versions with unchanged storage reservations
#### Scenario: File operation in progress
- **WHEN** a file helper holds an exclusive write lease
- **THEN** lifecycle cannot steal it or delete its helper, and no concurrent workspace write is admitted
### Requirement: Stop and cleanup are facts
The platform SHALL persist exact stopped execution facts before cleanup/admission release and SHALL retain unresolved ownership without replay.
#### Scenario: Lost stop response
- **WHEN** runner stops but its response is lost
- **THEN** reconciliation queries the original command and settles its receipt once without replaying it
#### Scenario: Cleanup failure
- **WHEN** removal fails after the stop receipt commits
- **THEN** durable cleanup state remains observable and exact cleanup can converge without another business effect
#### Scenario: Unproven termination
- **WHEN** only lease expiry, transport close or Docker404 is known
- **THEN** the environment remains BLOCKED with retained ownership until exact stop evidence or valid host proof is available
### Requirement: Signed protocol compatibility
ENVIRONMENT_V3 SHALL bind intent and physical identity without weakening historical V2 validation or old migration bytes.
#### Scenario: Forged or old owner
- **WHEN** an algorithm, audience, body, policy, generation or fence is wrong
- **THEN** runner refuses execution and result submission
#### Scenario: Additive upgrade
- **WHEN** V13 upgrades to V14
- **THEN** previous files/runtime baseline and supported old-program read paths remain valid without changing V1-V13
### Requirement: Whole-stack bounded resources
The platform SHALL validate the complete cold-start and active sandbox/file profile within2887MiB total ceilings,768MiB host reserve and5GiB disk margin.
#### Scenario: Concurrent required capabilities
- **WHEN** login,100MiB bounded streaming and trusted sandbox canary execute in the admitted profile
- **THEN** required services stay alive and every helper/runtime budget is counted
#### Scenario: Insufficient resources
- **WHEN** required functionality cannot fit or a floor fails
- **THEN** startup/release stops with FAIL evidence, without swap expansion or weakened isolation
### Requirement: Truthful lifecycle interface
The browser SHALL display actual lifecycle, ETag/allowed actions and original operation status without treating storage READY as environment readiness.
#### Scenario: Refresh and unknown result
- **WHEN** a response is lost or an operation is BLOCKED
- **THEN** refresh queries the original operation and no blind retry or automatic wake occurs
#### Scenario: Another browser advances the environment
- **WHEN** the caller reads its original request or operation under the workspace after another accepted operation changes the current version
- **THEN** current membership is checked, the original stable operation metadata is returned, and unrelated request/space identities return404 without physical paths or container data
### Requirement: Verified and released are distinct
R3 SHALL be released only after protected local promotion and same-artifact cloud integration, observation, consistent restore and R2 rollback tests pass.
#### Scenario: Successful release
- **WHEN** all mandatory gates pass and the exact R3 candidate restarts after rollback rehearsal
- **THEN** production-r3 tag binds artifacts/configuration and R3 is RELEASED with legacy stopped
#### Scenario: Cloud failure
- **WHEN** a cloud gate fails
- **THEN** original R2 is restored, failures remain, and no R3 production tag is created
