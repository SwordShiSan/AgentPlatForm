# Framework alignment before R2.2

## Why
Direct JDBC plumbing, manual messaging lifecycle and duplicated HTTP validation increase maintenance cost and obscure the full platform architecture. The user requested MyBatis based on the legacy structure, framework reuse assessment, and concrete implementation guidance for all unfinished stages.

## What Changes
- Plan a bounded R2.C correction: MyBatis/Spring transactions, Spring AMQP, Spring Security/typed validation and evidence-based AgentScope adapters.
- Preserve authoritative ownership, budgets, idempotency, checkpoints and all existing public contracts.
- Add architecture and implementation blueprints to R2–R9 and a legacy capability crosswalk. Gate R2.2 on completed correction.
- This delivery prepares documentation only; implementation and runtime verification remain pending an implementation Plan review.

## Capabilities
### New Capabilities
- `framework-alignment`: framework integration requirements and implementation-readiness gates.

### Modified Capabilities
None: existing runtime requirements remain binding; no public API or migration change is proposed by this documentation delivery.

## Impact
Future changes affect infrastructure/application/trigger adapters and tests. This turn changes only planning documents, navigation and OpenSpec. Cloud R1, Java21, Boot4.0.8, AgentScope2.0.4 and V1–V6 remain unchanged. Canonical body is outside Git; see repository README links.
