## MODIFIED Requirements

### Requirement: CSRF and origin boundary
The system SHALL validate exact configured origins and session-bound CSRF for writes including login. Login SHALL rotate pre-authentication session and CSRF. Cookies SHALL be HttpOnly, SameSite=Lax and Secure by default; explicit localhost development and the approved r1-http cloud configuration SHALL disable Secure. Cloud HTTP SHALL permit only the configured existing domain/IP origins; HTTPS remains reserved and unimplemented by the user decision2026-10-17.

#### Scenario: Foreign origin or missing token
- **WHEN** a write has a foreign origin or missing/incorrect CSRF token
- **THEN** it is rejected without changing authentication state

### Requirement: Local identity UI and evidence
The system SHALL provide generated contracts for CSRF, login, current user and logout, plus accessible login/account views. Acceptance SHALL include real PostgreSQL, real API browser tests, migration compatibility and failure/recovery evidence. Production SHALL remain R0 until R1.4 local promotion, cloud observation, independent restore and rollback gates pass, after which the same accepted identity/chat artifacts SHALL be released as R1.

#### Scenario: Logout or expired session
- **WHEN** a user logs out or receives an unauthorized response
- **THEN** the UI clears user-scoped state and returns to login without displaying unimplemented business controls

