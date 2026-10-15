## MODIFIED Requirements
### Requirement: Traceable acceptance
The workflow SHALL require a validated OpenSpec change, real local/cloud Docker and UI checks, protected PR/non-force branch policies and two PR promotions before marking a small stage VERIFIED. From the approved R0.4 policy2026-10-15 GitHub SHALL serve version management only: Actions and hosted build triggers are disabled, retired required CI contexts are removed, and exact-source local/cloud evidence replaces hosted checks without fabricated success statuses. Historical R0.1 CI evidence remains valid history.

#### Scenario: Incomplete acceptance
- **WHEN** Docker, protection, mandatory functional tests or PR promotion is missing
- **THEN** the step remains IMPLEMENTING with explicit NOT_RUN or blocked evidence and no dependent stage begins

#### Scenario: Local acceptance replaces retired CI
- **WHEN** protected branches receive a locally/cloud-verified candidate
- **THEN** promotion uses the matching source and immutable-image evidence through PRs, without scheduling GitHub builds or bypassing branch protection
