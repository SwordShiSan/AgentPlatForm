## ADDED Requirements
### Requirement: Reviewed foundation security rebuild
The platform SHALL use the approved container distributions and compatible patch families, lock linux/amd64 manifests and source/package/patch identities before building locally or in CI, preserve host OS and storage formats, and disclose rebuilt products.

#### Scenario: Foundation or source identity drifts
- **WHEN** a build input differs from its manifest, source commit, package or patch hash lock
- **THEN** the build fails and cannot export an accepted cloud candidate

#### Scenario: Vendor backport or unreachable component is assessed
- **WHEN** an exact advisory is proposed as NOT_AFFECTED
- **THEN** exact product/component and current source or file ownership evidence are hashed, reviewed and invalidated by changed inputs, while raw findings remain available

#### Scenario: Foundation regression fails
- **WHEN** rebuilt images fail compatibility, audit, quota, recovery or resource acceptance
- **THEN** the stage remains FAIL or NOT_RUN, temporary new resources stop and preserved legacy services are restored without an OS upgrade

### Requirement: Reviewed security patches and scoped applicability
The framework baseline SHALL remove unused Testcontainers scaffold and stale
probe jars after source and SBOM ownership review, retain AgentScope2.0.4 and
all existing tests, and refresh actual dependency/scanning evidence.

#### Scenario: An unused scaffold owns shaded vulnerable classes
- **WHEN** source and executed test review prove that the scaffold is unused
- **THEN** remove its declaration, rebuild clean probe classpaths and rerun all regression and production/test scans before acceptance

The platform SHALL use approved Boot4.0.8/Tomcat11.0.25/Jackson2.21.7/Jackson3.1.7/Vitest4.1.11 with Java21/SDK2.0.4, preserving raw scans and exact product-bound applicability evidence.

#### Scenario: MCP server advisory matches
- **WHEN** GHSA-8jxr-pr72-r468 matches MCP0.17.2
- **THEN** each product requires source/runtime no-server proof before OpenVEX NOT_AFFECTED, invalidated by changed source/dependency/entry

#### Scenario: Previous private state is restored
- **WHEN** patched Jackson2 reads previous-baseline state
- **THEN** scope, framework version, manifest and CAS remain compatible without Flyway V1 changes

### Requirement: Protected object configuration and recovery failure
The system SHALL verify disabled STS using signed wire-level requests and
healthy S3 access before and after the requests; SDK XML parser errors alone
SHALL NOT establish non-availability.

#### Scenario: Disabled STS route returns an empty method refusal
- **WHEN** signed STS POST actions in body and query receive empty405 and S3 remains functional
- **THEN** the denial probe records the explicit refusal without parsing it as AWS XML

#### Scenario: STS denial transport or success is ambiguous
- **WHEN** a request times out, cannot connect, returns success or unexpected status/body
- **THEN** the probe fails rather than treating the parsing or transport failure as denial

The platform SHALL disable unused IAM/STS, protect KEK wrapping material and install restore cleanup before side effects, checking actual restored hashes and quotas.

#### Scenario: Restore precondition or readiness fails
- **WHEN** capacity, file hash, mount or health validation fails
- **THEN** temporary resources stop, evidence remains and original XFS UUID/data are unchanged

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
