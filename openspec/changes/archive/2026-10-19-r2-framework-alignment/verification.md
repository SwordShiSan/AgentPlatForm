# Current acceptance (2026-10-19)

All mandatory local gates PASS. Implementation PR50 (step→phase) and51 (phase→main) are merged; accepted main8391e5b0885d4af30d09d1f7ee799d2cb853fa80. R2.C VERIFIED. This change is archived with protected documentation promotion; final merge/delivery identities are recorded separately in the protected handoff. Source cd879ff273846c47a4da483a17b4a3b50462eb93; exact backend/frontend trees and four immutable image/index/manifest/config/archive hashes are in deploy/r2.c-acceptance.json. V1–V6 and public wire contracts unchanged, cloud R1 unchanged, real provider calls0.

| Group | Current result | Evidence |
|---|---|---|
| Persistence/ownership | PASS | Maven151 includes identity26/chat32/ownership17; actual Mapper XML, rollback, uniqueness, snapshots, UUID/jsonb/time, lease-before-commit revocation and expiry, all old-fence writes/CAS/delete, corrupt checkpoint/proof rejection |
| Messaging/process faults | PASS | Six actual Linux worker crash windows; two workers/global slot1; confirm/ACK loss and real DB/broker interruptions; UNKNOWN retained without replay |
| SDK | PASS within scope | Final non-root immutable probe57; old state/Jackson isolation; three public codec/compactor experiments; method/source/limitations in deploy/r2.c-sdk-reuse.json |
| HTTP/browser | PASS | Real network auth/Origin/CSRF/strict DTO/restart/revocation tests; Playwright16 across identity2/home5/ownership3/config4/http1/diagnostics1; responsive375/768/1440, zoom, keyboard, themes |
| Resources/secrets | PASS | 12 serial tasks/13 samples,2528MiB limits, minimum sampled available5642.53MiB, no OOM; API ciphertext read denied, worker proof read denied, sentinel leaks0 |
| Engineering/governance | PASS | Vue22/typecheck/build/contracts normal+invalid; six module trees/171 hash-locked artifacts; source JDBC boundary95 files/3 exact exceptions; links30, canonical290 and31 future-step blueprints; strict13 |

This local Docker VM is larger than the minimum cloud host. Samples are not absolute peaks or a cloud/higher-concurrency promise. SDK compaction remains off until R6; full checkpoint continuation remains R2.4. No deployment, tools or real provider enabled. Local fixture stopped with data/images retained. Raw logs and secrets stay protected; only sanitized receipts are in Git. Historical early compile/SQL/HTTP integration failures and Node sandbox EPERM are retained locally and followed by clean passing regressions.

## Promotion and scope

PR50: https://github.com/SwordShiSan/AgentPlatForm/pull/50 ; PR51: https://github.com/SwordShiSan/AgentPlatForm/pull/51 . Actions remain disabled; no required check was bypassed or synthesized. Application trees match the accepted source; closure commits only change governance/evidence/scripts. R2 remains1/4, unreleased; R2.2 correction block removed but separate Plan/OpenSpec remains mandatory. Local fixture stopped, cloud unchanged. Canonical body and sanitized evidence packaged separately, hash-checked protected local copies retained through at least2026-11-18; no cloud body sync claimed.

## Historical engineering and planning records

# Current acceptance (2026-10-19)

Implementation approved, engineering PASS, final immutable runtime acceptance pending. Maven clean verify151/151, zero failed/skipped; Vue22, typecheck/build, contracts normal/invalid PASS. Real PG/MQ included. MyBatis4.0.0/Boot4.0.8/AgentScope2.0.4;171 external artifacts locked. V1–V6 unchanged. Source candidate will be fixed before Docker acceptance.

Pending: Linux non-root probes, final-image two-worker crash windows/outages/resources, full real-API browser regressions, source/image/evidence binding and two-level PR promotion. No cloud change or real provider request. Protected details: .cache/r2c-local/engineering.json; final acceptance record will distinguish source SHA and merged documentation SHA.

Historical planning-only receipt and earlier NOT_RUN matrix below are retained as process history, not the current engineering result.

# Verification

## Planning delivery
Source review: completed against recorded source baselines; no runtime capability inferred from source alone. Canonical audit and all R2–R9 stage blueprints prepared. OpenSpec --all --strict: 13/13 PASS; repository links:25 PASS; git diff --check:PASS. Canonical links and 31 unfinished-step blueprints checked by the planning validator; exact counts and hashes are in evidence/framework-alignment/planning-verification.json. This is documentation validation only.

## Historical planning matrix (NOT_RUN at preparation)
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
