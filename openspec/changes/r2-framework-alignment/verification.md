# Current acceptance (2026-10-19)

Implementation approved, engineering PASS, final immutable runtime acceptance pending. Maven clean verify151/151, zero failed/skipped; Vue22, typecheck/build, contracts normal/invalid PASS. Real PG/MQ included. MyBatis4.0.0/Boot4.0.8/AgentScope2.0.4;171 external artifacts locked. V1–V6 unchanged. Source candidate will be fixed before Docker acceptance.

Pending: Linux non-root probes, final-image two-worker crash windows/outages/resources, full real-API browser regressions, source/image/evidence binding and two-level PR promotion. No cloud change or real provider request. Protected details: .cache/r2c-local/engineering.json; final acceptance record will distinguish source SHA and merged documentation SHA.

Historical planning-only receipt and earlier NOT_RUN matrix below are retained as process history, not the current engineering result.

# Verification

## Planning delivery
Source review: completed against recorded source baselines; no runtime capability inferred from source alone. Canonical audit and all R2–R9 stage blueprints prepared. OpenSpec --all --strict: 13/13 PASS; repository links:25 PASS; git diff --check:PASS. Canonical links and 31 unfinished-step blueprints checked by the planning validator; exact counts and hashes are in evidence/framework-alignment/planning-verification.json. This is documentation validation only.

## Runtime matrix (all NOT_RUN)
| Group | Normal/boundary/failure/recovery assertions | Status |
|---|---|---|
| FA-T01 persistence | Real Mapper resources/types, atomic rollback, concurrent unique keys, snapshot cursor | NOT_RUN |
| FA-T02 ownership | DB lease expiry before commit, old fence all writes, SDK CAS/delete, checkpoint hashes | NOT_RUN |
| FA-T03 messaging | Durable claim before ACK, confirm/ACK loss, DB/MQ outage, two workers one slot | NOT_RUN |
| FA-T04 HTTP | Existing login/rotation/expiry, invalid DTO/CSRF/Origin, revocation, dual-user isolation | NOT_RUN |
| FA-T05 SDK | Old state, Jackson2/3 isolation, cancellation, unknown usage, model/tool/checkpoint crash windows | NOT_RUN |
| UI/resources | R1 full browser regression and bounded connection/thread/memory lifecycle | NOT_RUN |

Commands at implementation: backend/mvnw.cmd verify; frontend pnpm typecheck/test/build/contracts:check/contracts:test/links:check; openspec validate --all --strict; stage-specific real PG/MQ/SDK/browser suites with exact invocation and source/image identities recorded. Never treat this command inventory as executed tests.

No Java/Vue/SQL/runtime configuration or production changes in the planning delivery. No real provider calls. No new VERIFIED/RELEASED status or PR promotion. External canonical body is delivered separately from Git.

## Approved implementation gate (2026-10-19)
User approved implementation of this same correction. MyBatis starter is pinned to4.0.0; its official POM returned HTTP200. Boot4.0.8 manages Spring dependencies. Runtime acceptance remains NOT_RUN until fresh evidence exists. Source802f46a; cloud R1 unchanged; real provider calls0.
