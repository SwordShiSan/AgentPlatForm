## ADDED Requirements
### Requirement: Authorized HTTP release boundary
The R0 release SHALL serve its existing shell on HTTP80 at agentplatform.lailihao.cloud and124.222.55.140 without DNS mutation or DNS/TLS acceptance gates,as explicitly approved by the user. It SHALL preserve existing API/schema and disabled business capabilities.
#### Scenario: A release request uses its configured Host
- **WHEN** the designated domain or public IP is requested on HTTP80
- **THEN** shell,health and readiness work without HTTPS redirection and internal dependency details remain private
#### Scenario: DNS or HTTPS is not validated
- **WHEN** service/IP/Host tests pass without DNS or TLS checks
- **THEN** record those checks outside the authorized scope rather than falsely claiming DNS/TLS verification
### Requirement: Guarded production lifecycle
The platform SHALL verify immutable images,capacity and XFS identity/quota before service startup,with bounded normal-service retries and no automatic migration or sandbox replay.
#### Scenario: A required mount or quota check fails
- **WHEN** production startup preflight fails
- **THEN** no service starts and no write enters the mount underlay
### Requirement: Reversible serial release
The release SHALL preserve old identities/data/policies and run old,new and restore stacks serially,restoring legacy availability on mandatory gate failure.
#### Scenario: Production acceptance fails
- **WHEN** a functional,isolation,resource or recovery assertion fails
- **THEN** stop new resources and restore exact legacy policies,entry and readiness with retained evidence
#### Scenario: Production acceptance succeeds
- **WHEN** fresh full regression,independent recovery and uninterrupted30minute observation pass
- **THEN** keep the new stack healthy and old stack suspended with rollback data intact
### Requirement: Evidence-bound stage release
The workflow SHALL use protected PRs and local/cloud acceptance without hosted builds,create an immutable production tag only after production gates pass,and update documentation and the existing skill consistently.
#### Scenario: Git publication temporarily fails
- **WHEN** the deployment is healthy but tag/record publication fails
- **THEN** retain DEPLOYED_PENDING_RELEASE and retry metadata without claiming RELEASED or replaying execution
#### Scenario: Release delivery completes
- **WHEN** tag,protected evidence hashes,documentation and skill updates are verified
- **THEN** mark R0 RELEASED with four verified small stages and keep R1 NOT_STARTED
