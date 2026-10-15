## ADDED Requirements
### Requirement: Local acceptance and project scope
The platform SHALL run builds and environment acceptance locally or on the approved cloud host. GitHub SHALL serve version management without automatic push or pull-request builds. The following current user policy supersedes earlier blanket scan-blocking and third-party rebuild requirements in this change: third-party image findings that do not affect project functionality do not require remediation; scans remain informational and SHALL NOT be misreported as clean. Functional failures and the agreed sandbox isolation, permissions, quota, resource and recovery requirements remain blocking. Third-party source modifications and costly environment rebuilds require prior explanation and explicit user agreement.

#### Scenario: A code branch is pushed
- **WHEN** project code is pushed or a pull request is opened
- **THEN** GitHub does not automatically build or provision the environment, and local/cloud acceptance evidence determines readiness

#### Scenario: A third-party image scan has findings without project functional impact
- **WHEN** the findings do not affect delivered project functions or agreed isolation behavior
- **THEN** retain the original findings without requiring vendor source repair or declaring the findings resolved

### Requirement: Official reproducible runtime baseline
The platform SHALL use the stable R0.3 official middleware identities and gVisor20261005.0 full package, with local project image builds and cloud identity verification. Third-party source-rebuild candidates SHALL NOT be required to start or accept the platform.

#### Scenario: Official component fails a required project function
- **WHEN** a reproducible failure remains after project configuration and script diagnosis
- **THEN** retain evidence and discuss costly source modification or stack replacement before proceeding with that item

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
The platform SHALL pin complete resolved dependencies, tooling and image identities, preserve scoped SBOM and license provenance, and retain informational vulnerability reports without requiring unrelated vendor remediation.

#### Scenario: Dependency or image changes
- **WHEN** a package hash, resolved version or imported image identity differs from the frozen baseline
- **THEN** acceptance fails and retains diagnostic evidence instead of silently accepting drift

#### Scenario: Audit is incomplete
- **WHEN** a scanner or vulnerability database is unavailable
- **THEN** report that audit as NOT_RUN, continue mandatory functional acceptance and never claim the missing scan passed

### Requirement: Contract and source evidence freeze
The platform SHALL freeze its implemented HTTP/event envelope and HC-1 baseline and map SDK source signatures to real executable assertions without claiming future business capability.

#### Scenario: Client regeneration
- **WHEN** generation runs against the frozen schemas
- **THEN** generated outputs match tracked files and existing readiness and health behavior remains compatible

### Requirement: Protected durable acceptance evidence
The platform SHALL preserve hashed, source-bound acceptance evidence on local and cloud storage with protected local/cloud retention of at least30days, excluding secrets and raw backups from public Git.

#### Scenario: CI artifact expires
- **WHEN** a short-lived CI download is unavailable
- **THEN** locally and cloud-retained evidence remains independently hash-verifiable

### Requirement: Minimal-host isolated recovery
The platform SHALL validate consistent storage restoration in independent temporary projects with single-stack resource admission, and restore legacy availability after acceptance or failure.

#### Scenario: Restore or acceptance fails
- **WHEN** a restore check fails or minimum host capacity is insufficient
- **THEN** new execution stops, evidence is retained and exact legacy containers and policies are restored without deleting user data

### Requirement: Honest step and production status
The platform SHALL promote R0.4 only after all mandatory engineering, functional, isolation, resource and recovery gates and two PRs pass, with informational audit results reported separately, and SHALL NOT claim production release without independent domain/TLS and production gates.

#### Scenario: Developer acceptance succeeds
- **WHEN** R0.4 gates and promotion pass but production entry is not configured
- **THEN** R0.4 is VERIFIED, R0 remains unreleased and R1 does not start
