## ADDED Requirements
### Requirement: Reproducible audited dependency baseline
The platform SHALL pin complete resolved dependencies, tooling and image identities, preserve scoped SBOM and license provenance, and reject unresolved high or critical vulnerabilities.

#### Scenario: Dependency or image changes
- **WHEN** a package hash, resolved version or imported image identity differs from the frozen baseline
- **THEN** acceptance fails and retains diagnostic evidence instead of silently accepting drift

#### Scenario: Audit is incomplete
- **WHEN** a vulnerability database cannot be obtained or a high/critical finding lacks resolution
- **THEN** the audit is NOT_RUN or FAIL and the stage cannot be promoted

### Requirement: Contract and source evidence freeze
The platform SHALL freeze its implemented HTTP/event envelope and HC-1 baseline and map SDK source signatures to real executable assertions without claiming future business capability.

#### Scenario: Client regeneration
- **WHEN** generation runs against the frozen schemas
- **THEN** generated outputs match tracked files and existing readiness and health behavior remains compatible

### Requirement: Protected durable acceptance evidence
The platform SHALL preserve hashed, source-bound acceptance evidence on local and cloud storage with CI retention of at least30days, excluding secrets and raw backups from public Git.

#### Scenario: CI artifact expires
- **WHEN** a short-lived CI download is unavailable
- **THEN** locally and cloud-retained evidence remains independently hash-verifiable

### Requirement: Minimal-host isolated recovery
The platform SHALL validate consistent storage restoration in independent temporary projects with single-stack resource admission, and restore legacy availability after acceptance or failure.

#### Scenario: Restore or acceptance fails
- **WHEN** a restore check fails or minimum host capacity is insufficient
- **THEN** new execution stops, evidence is retained and exact legacy containers and policies are restored without deleting user data

### Requirement: Honest step and production status
The platform SHALL promote R0.4 only after all mandatory audit/runtime/recovery gates and two PRs pass, and SHALL NOT claim production release without independent domain/TLS and production gates.

#### Scenario: Developer acceptance succeeds
- **WHEN** R0.4 gates and promotion pass but production entry is not configured
- **THEN** R0.4 is VERIFIED, R0 remains unreleased and R1 does not start
