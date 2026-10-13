## ADDED Requirements

### Requirement: Small-stage stable promotion and major-stage production release
The workflow SHALL keep `main` at the latest locally Docker-verified small-stage commit and SHALL treat major-stage production release as a separate gate tracked by an immutable production tag and image digest.

#### Scenario: A small stage passes local acceptance
- **WHEN** its Docker-backed frontend, backend, required dependencies, UI and regression gates pass
- **THEN** the step is reviewed and merged into its phase branch, the phase snapshot is reviewed and merged into main, and main is synchronized back into the phase branch before the next small stage starts

#### Scenario: A major stage passes cloud acceptance
- **WHEN** all small stages are verified and the exact locally canaried image digest passes cloud tests and observation
- **THEN** the major stage is marked RELEASED and receives an immutable production tag without delaying or replacing the per-small-stage main promotions

#### Scenario: Cloud release validation fails
- **WHEN** the candidate image fails online validation
- **THEN** production is rolled back to its previous production tag while main retains the latest locally verified small stage; a fix uses a new step branch and must pass local gates and PR promotion before release validation is retried
