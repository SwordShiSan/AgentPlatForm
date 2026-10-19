# Framework alignment before R2.2

## Why
Direct JDBC plumbing, manual messaging lifecycle and duplicated HTTP validation increase maintenance cost and obscure the full platform architecture. The user requested MyBatis based on the legacy structure, framework reuse assessment, and concrete implementation guidance for all unfinished stages.

## What Changes
- Implement the approved bounded R2.C correction: MyBatis/Spring transactions, Spring AMQP, Spring Security/typed validation and evidence-based AgentScope adapters.
- Preserve authoritative ownership, budgets, idempotency, checkpoints and all existing public contracts.
- Add architecture and implementation blueprints to R2–R9 and a legacy capability crosswalk. Gate R2.2 on completed correction.
- Implementation and all local engineering/immutable-runtime/browser gates PASS; protected promotion remains pending.

## Capabilities
### New Capabilities
- `framework-alignment`: framework integration requirements and implementation-readiness gates.

### Modified Capabilities
None: existing runtime requirements remain binding; no public API or migration change is introduced by this implementation.

## Impact
This implementation changes persistence, messaging, HTTP adapters and tests; no cloud deployment or public wire/schema change. Cloud R1, Java21, Boot4.0.8, AgentScope2.0.4 and V1–V6 remain unchanged. Canonical body is outside Git; see repository README links.

## Approved implementation gate (2026-10-19)
User approved implementation of this same correction. MyBatis starter is pinned to4.0.0; its official POM returned HTTP200. Boot4.0.8 manages Spring dependencies. Runtime acceptance remains NOT_RUN until fresh evidence exists. Source802f46a; cloud R1 unchanged; real provider calls0.
