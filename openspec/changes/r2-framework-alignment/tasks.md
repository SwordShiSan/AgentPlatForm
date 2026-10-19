## 1. Planning preparation
- [x] 1.1 Inspect new platform, legacy MyBatis/AMQP and fixed AgentScope source; record evidence and gaps.
- [x] 1.2 Draft canonical corrective plan, ADR and architecture constraints; enrich R2–R9 and capability crosswalk.
- [x] 1.3 Prepare proposal/specs/design/tasks/verification and distinguish pending runtime work.
- [x] 1.4 Validate strict, links and documentation consistency; save a planning-only evidence receipt.
- [x] 1.5 User approved concrete implementation Plan; pin MyBatis4.0.0, verified official metadata. Historical approval gate; subsequent task3.3 and runtime results are recorded below.

## 2. Persistence
- [x] 2.1 Resolve compatible MyBatis patch and configure Mapper/XML/type handlers/shared Spring transactions.
- [x] 2.2 Migrate identity with real PostgreSQL atomicity/session/revocation regression.
- [x] 2.3 Migrate chat/config/credential and ownership/ledger/budget/checkpoint adapters; preserve lock and before-commit guard.
- [x] 2.4 Remove replaced business JDBC plumbing and enforce scoped exceptions/DDD boundaries.

## 3. Framework adapters
- [x] 3.1 Integrate Spring AMQP confirms/returns/manual ACK and managed lifecycle; run real failure windows.
- [x] 3.2 Integrate Security opaque-session adapter, Origin/CSRF and typed validation without public contract drift.
- [x] 3.3 Evaluate and implement justified AgentScope adapter simplification; document state/compaction gaps and regressions.

## 4. Acceptance and promotion
- [x] 4.1 Run engineering, R1, R2.1, two-worker PG/MQ, SDK compatibility, browser and constrained resource regressions; real provider calls0.
- [x] 4.2 Bind final source/images/schema/commands/results and canonical document delivery hashes.
- [ ] 4.3 Review step→phase→main PRs; archive only after mandatory gates pass; update correction status and unblock R2.2.
