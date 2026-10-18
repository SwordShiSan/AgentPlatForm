# Design

## Context
Baseline main20b7690; legacya142b102; SDK2.0.4 source0b6743b. R2.1 historical evidence is retained, not reused as proof of the new framework adapters. R2.C is a supplemental correction before R2.2, not a fifth R2 business step.

## Goals / Non-Goals
Replace generic infrastructure plumbing with maintained frameworks while preserving execution semantics and platform completeness. Do not migrate old data, modify V1–V6, enable tools, call real providers, deploy, or fork third-party source. Current delivery is a reviewable plan, not runtime implementation.

## Decisions
1. MyBatis Boot4-compatible official starter, exact patch resolved and locked during implementation preparation. Repository→DAO→PO/XML follows the legacy organization, with transactions in the application layer and a pure domain.
2. Same DataSource/Spring transaction manager for every Mapper in a unit of work. Explicit lock order and a before-commit ownership guard preserve database-time lease semantics. No per-Mapper commits or asynchronous transaction inheritance.
3. Spring AMQP owns connection/container lifecycle, correlated confirms and returns. MANUAL ACK follows durable claim commit; outbox/inbox and DB owner remain authoritative.
4. Spring Security adapts the current opaque session, Origin and CSRF contract. Typed DTO validation replaces boundary Map casts. Live resource authorization remains inside use cases/transactions.
5. AgentScope already owns the actual loop and provider implementation. Evaluate reducing redundant Model conversions and using public state/compaction hooks; retain narrow proven adapters where SDK semantics do not meet platform guarantees. Never enable unsafe builtin tools to increase reuse.
6. Each remaining stage must specify framework APIs, module/package responsibilities, end-to-end data flow, transactions, frontend and observable failure/recovery assertions. Canonical plans contain the detailed blueprints; no duplicate body here.

## Risks / Trade-offs
- Framework transactions can accidentally weaken fence/lease checks or split atomic writes: same-connection rollback and before-commit expiry tests are mandatory.
- Listener concurrency and reconnection can duplicate ownership: retain real two-worker crash tests.
- Security defaults may alter cookies/statuses or cache stale membership: exact-contract and revocation regressions required.
- SDK state API conflicts may require a narrow adapter: prove compatibility, do not force a fork.
- Future internal runner transport and legacy A2A scope require explicit phase decisions before their implementation.

## Migration Plan
Identity persistence first, then chat/config, ownership/budget/ledger/state; remove replaced active write paths. Follow with MQ/Web adapters, SDK experiments, full regression and two-level PR promotion. Default no DDL change. Semantic/codec changes require bundle compatibility and draining or retaining old bundles. On failure retain previous candidate and evidence; do not promote or deploy.

## Open Questions
Exact MyBatis patch and SDK compaction/state adapter feasibility need implementation preparation evidence; neither is claimed tested. R3 internal authentication and R7 legacy A2A disposition are phase-specific decisions, not permission to invent a protocol now.
