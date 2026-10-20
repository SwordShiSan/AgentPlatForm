# Verification
Current status IMPLEMENTING; runtime gates NOT_RUN until measured, source baseline9412d2c. Cloud unchanged, provider calls0.

Intermediate engineering: Maven verify158 tests,0 failures/errors/skips; targeted ChatPostgresTest36 rerun includes historical V6 prefix/empty-history V7 backfill and canonical snapshot preservation; ChatProjectionTest2 passed after the nullable envelope fix. Vue32 tests/typecheck/frozen install/flag-off+on builds/contracts/links and strict14 passed. The real HTTP MVC PoC passed5 cases including8/9 admission, stopped-reader35s bound, revocation and pre-header cursor/oversize errors. These transport tests use stubbed application services; real API DB-pool/browser/image/observation receipts are still required. Logs remain protected under `.cache/r22-*.log`; exact committed source/image binding follows candidate freeze.

Candidate8c1ac78 is provisional: Linux58 passed and real protocol passed (8/9 admission, no held idle transactions, privilege/floor/snapshot assertions). First browser run14/17 passed;3 failed. Preserve initial logs/contexts privately. A watchdog interval overflow under delayed reads and incomplete slow-task test cleanup were identified; update and revalidate before final candidate/observation. No stage promotion or VERIFIED claim follows these partial results.

Correction regression: full Maven verify160 tests with0 failures/errors/skips; Vue34 tests and other engineering gates passed. Backend candidate2973cfc passed58 non-root Linux tests. A subsequent frontend-only exact-query snapshot guard is being verified; its gateway is rebuilt while retaining unchanged backend/probe/provider identities with input-tree equivalence. Real runtime/browser/fault/observation/rollback gates still remain mandatory.
| Gate | Required assertions | Status |
|---|---|---|
| Lifecycle PoC | real8/9streams, backpressure,35s cleanup, DB pool not retained | NOT_RUN |
| PG/V7 | fresh/upgrade, snapshot commit/rollback, prefix boundaries/concurrency/rollback/least privilege | NOT_RUN |
| Protocol | all cursor precedence/bounds,410 rebase, Unicode/duplicates/old-generation, privacy | NOT_RUN |
| Engineering | Maven verify/DDD/Enforcer,Vue type/test/build, generated diff/invalid fixtures,strict/links/fullstage diff | NOT_RUN |
| Runtime | actualPG/MQ/two workers owner/fence/UNKNOWN, auth/DB/broker failure, Linux old state/Jackson | NOT_RUN |
| Browser | real login/chat/config/diagnostics, refresh/switch/offline/expiry/dual-user/responsive/keyboard/themes | NOT_RUN |
| Resources |30min deterministic single-slot with reconnect/GC,2528 initial/2887 max/768 reserve/no swap extension | NOT_RUN |
| Recovery/delivery | previous R2.C image on V7 with collector disabled, exact artifacts/PRs/archive/protected hashes | NOT_RUN |
Commands use wrapper verify, frontend scripts, openspec validate --all --strict, isolatedr22 Docker acceptance. No source change after final candidate without affected revalidation. Never count skipped tests or setup fixtures as actual provider evidence.
