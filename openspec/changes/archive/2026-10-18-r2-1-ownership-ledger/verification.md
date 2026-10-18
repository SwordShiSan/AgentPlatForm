# Verification

Status: R2.1 VERIFIED; implementation PR46/47 merged. R2 is1/4 VERIFIED and NOT RELEASED. Backend candidate `2e5307b2f4a99e32790787bf824c9758c34282a5`, exact backend tree `24e08b05d301d9b4095057dff0d7e7b4673d3c9e`. Later changes are host tooling and evidence only. Cloud R1 unchanged; real vendor requests **0**.

| Gate | Actual evidence | Status |
|---|---|---|
| Specification | Approved Plan; five artifacts; strict before code exit0 and final strict12/12 | PASS |
| Engineering | Maven Wrapper install/verify137, DDD/Enforcer, real chat32/identity26/ownership16/handoff1; frontend22/type/build, contracts/regeneration/invalid fixtures, links | PASS |
| SDK | Linux nonroot53, old state/Jackson2/3; 11 SDK classes byte-equal in final API; final Linux worker crash suite rerun | PASS |
| Database | Fresh6, upgrade5→6, runtime schema1, rollback/idempotency/budget/ordering, V1–V5 unchanged | PASS |
| Ownership/MQ | Two real worker processes; single slot, ACK/confirm loss, redelivery; broker queued without PG fallback; DB failure fail-closed; old fence/live revocation rejected | PASS |
| Ledger/recovery | Six SIGKILL windows; signed exact-container stop plus DB lease expiry; safe requeue generation2, terminal convergence; response-before-round stays BLOCKED/UNKNOWN, one fixture request | PASS |
| Tool/checkpoint | Real SDK deterministic test-tool boundaries before/after effect; no business tools; immutable snapshots, missing/corrupt/bundle/codec mismatch and fake/stale proof rejected | PASS |
| Browser | Three real-API Chromium tests: chat/refresh/partial/diagnostic isolation/cancel; 375/768/1440, themes, keyboard,200% | PASS |
| Resources/secrets | 12 serial tasks, two workers idle, total2528MiB≤2887; host reserve≥768; no OOM/container swap expansion; fake-key/public-state/privilege checks | PASS |
| Promotion/handoff | Exact source/images bound in deploy/r2.1-acceptance.json; implementation PR46/47 merged; two protected local package copies verified; archive-only PRs recorded by final receipt | PASS |

Commands (all final commands exit0): Maven Wrapper install with real isolated PG/MQ and R21_ACCEPTANCE=1; pnpm typecheck/test/build, contracts:check/contracts:test/links:check; openspec validate --all --strict; execution-acceptance.py crashes/outages/continuous; Playwright ownership.spec.ts; protected final physical-counter/secret/privilege audit.

Protected evidence: `.cache/r21-local/acceptance`, XML suites, browser log/screenshots and immutable image export manifests; delivery index under `evidence/r2.1`. OCI index, amd64 manifest and true config ID are distinct. Local-only images have no registry digest; no fabricated push claim.

Historical failed SDK snapshot CAS, MQ health throttling and DB-outage marker collection are retained with fixes and successful reruns. No third-party source changes. The current Docker Desktop VM has more RAM than the minimum cloud host: bounded caps and sampled headroom are local evidence, not a cloud-capacity claim. R2.2–2.4 and full checkpoint continuation NOT_RUN/deferred; deterministic results do not prove real vendor recovery.

Implementation PR46/47 merged; accepted main a9583324c9a505150718a734acdddb4fa9a95d1b. Archive-only promotion follows, without changing the accepted backend or rebuilding images. Protected local body/evidence packages and SHA256 receipt: evidence/r2.1/r2.1-handoff.json, retained through2026-11-18.
