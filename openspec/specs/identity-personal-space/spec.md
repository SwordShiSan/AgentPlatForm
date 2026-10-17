# identity-personal-space Specification

## Purpose
Provide operator-provisioned personal identities, durable sessions, CSRF/origin protection and tenant isolation, accepted locally without deploying identity to the R0 production service.
## Requirements
### Requirement: Atomic operator provisioning
The system SHALL create user, unique PERSONAL space, OWNER membership and 10GiB logical storage policy in one transaction. Operators SHALL supply passwords through console or protected files. Bootstrap SHALL be single-use, and provisioning request IDs SHALL be idempotent with input conflict rejection.

#### Scenario: Concurrent normalized names
- **WHEN** two provisioning transactions use the same normalized username
- **THEN** at most one user and one complete personal-space bundle exist

#### Scenario: Transaction fails midway
- **WHEN** provisioning fails after a user insert
- **THEN** no partial user, space, membership or storage policy remains

### Requirement: Durable bounded authentication
The system SHALL hash passwords using Argon2id m=65536KiB,t=3,p=1 and store only session token hashes. Tokens SHALL contain at least 256 random bits, expire after 7 days or 24 hours idle, and be revoked on logout, disable or password reset. Password work SHALL have a single concurrent slot. Login SHALL enforce 20 attempts/IP/minute and five failures/account/15min with a 15min lock.

#### Scenario: Revocation and restart
- **WHEN** an application restarts with an unexpired session
- **THEN** the persisted session remains valid unless the user was disabled, password reset or session revoked

#### Scenario: Authentication dependency fails
- **WHEN** the database is unavailable
- **THEN** authentication fails closed with a sanitized retryable dependency error

### Requirement: CSRF and origin boundary
The system SHALL validate exact configured origins and session-bound CSRF for writes including login. Login SHALL rotate pre-authentication session and CSRF. Cookies SHALL be HttpOnly, SameSite=Lax and Secure by default; explicit localhost development and the approved r1-http cloud configuration SHALL disable Secure. Cloud HTTP SHALL permit only the configured existing domain/IP origins; HTTPS remains reserved and unimplemented by the user decision2026-10-17.

#### Scenario: Foreign origin or missing token
- **WHEN** a write has a foreign origin or missing/incorrect CSRF token
- **THEN** it is rejected without changing authentication state

### Requirement: Personal-space authorization
The system SHALL derive actor identity from the cookie and check current active user/membership for every access. Platform administrator status SHALL NOT grant other users' content access.

#### Scenario: Foreign space selection
- **WHEN** user A selects user B's personal space
- **THEN** access is denied without revealing B's resources

### Requirement: Local identity UI and evidence
The system SHALL provide generated contracts for CSRF, login, current user and logout, plus accessible login/account views. Acceptance SHALL include real PostgreSQL, real API browser tests, migration compatibility and failure/recovery evidence. Production SHALL remain R0 until R1.4 local promotion, cloud observation, independent restore and rollback gates pass, after which the same accepted identity/chat artifacts SHALL be released as R1.

#### Scenario: Logout or expired session
- **WHEN** a user logs out or receives an unauthorized response
- **THEN** the UI clears user-scoped state and returns to login without displaying unimplemented business controls
