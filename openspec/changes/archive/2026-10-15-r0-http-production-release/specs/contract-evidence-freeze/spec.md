## MODIFIED Requirements
### Requirement: Honest step and production status
The platform SHALL promote R0.4 only after its mandatory engineering,functional,isolation,resource and recovery gates and two PRs pass,with informational audit results reported separately. A major stage SHALL require independently verified production deployment,observation,evidence and an immutable production tag. For R0 the user-approved2026-10-15 HTTP-only plan supersedes the former DNS/TLS precondition:HTTP80 service,public-IP and configured Host routing are mandatory;DNS and TLS are outside acceptance and SHALL NOT be falsely reported verified.

#### Scenario: Developer acceptance succeeds
- **WHEN** R0.4 gates and promotion pass but production acceptance has not completed
- **THEN** R0.4 is VERIFIED,R0 remains unreleased and R1 does not start

#### Scenario: Authorized HTTP production acceptance succeeds
- **WHEN** all R0 HTTP production,recovery,observation,protected evidence and immutable-tag gates pass
- **THEN** mark R0 RELEASED without claiming DNS/TLS verification and keep R1 NOT_STARTED until separately planned
