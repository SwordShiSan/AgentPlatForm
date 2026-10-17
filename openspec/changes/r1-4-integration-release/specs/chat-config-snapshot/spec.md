## MODIFIED Requirements

### Requirement: Local bounded verification
The platform SHALL expose only public message blocks and sanitized audit correlation, preserve all R1.2 execution protections, and verify Flash/Pro and PLATFORM/USER with minimal real calls, cumulative accounting and provider remote quota as the acceptance boundary. The approved R1.4 decision removes fixed twenty-call/one-CNY acceptance blocking without disabling business budgets.
#### Scenario: Unknown prior charges
- **WHEN** R1.3 or later acceptance starts
- **THEN** existing physical call counts and UNKNOWN reservations remain accounted for and are not reset
#### Scenario: Browser lifecycle
- **WHEN** a user refreshes a draft, reconnects a stream or changes accounts
- **THEN** drafts and persisted events remain isolated, accepted work is not duplicated, and logout or identity expiry clears local private state

