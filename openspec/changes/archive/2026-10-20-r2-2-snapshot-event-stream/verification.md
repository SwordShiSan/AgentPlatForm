# Current acceptance conclusion (2026-10-20)

All mandatory engineering and exact-candidate runtime gates PASS. Maven160, Vue34, Linux non-root58, actual API browser18/18, six actual worker crash windows, broker/active-DB failure,12 sequential tasks, retention concurrency and prior R2.C image/V7 compatibility passed. The30minute observation included actual scheduled GC and an in-window dependency interruption/recovery. Detailed measured values and hashes are in `deploy/r2.2-acceptance.json`.

Actual Docker host is approximately8GiB; combined container limits2528MiB are below2887MiB and sampled host reserve exceeds768MiB. Sampling is not an absolute peak or minimum3.6GiB physical-host certification. Real supplier calls0; cloudR1 unchanged. Historical FAIL/PARTIAL/NOT_RUN entries below are preserved as process history and superseded only by these explicit passing reruns. Public receipt excludes raw logs, browser contexts/form values, SQL, secrets and proof signatures.

Implementation promoted through protected step→phase→main PRs 56,57. R2.2 VERIFIED; R2 2/4 VERIFIED, NOT RELEASED. This archival/receipt update is promoted through the same protection; final merge and protected delivery hashes are recorded outside Git in evidence/r22/r22-handoff.json. No new runtime build is implied by metadata-only commits.

## Historical process records

# Current acceptance conclusion (2026-10-20)

All mandatory engineering and exact-candidate runtime gates PASS. Maven160, Vue34, Linux non-root58, actual API browser18/18, six actual worker crash windows, broker/active-DB failure,12 sequential tasks, retention concurrency and prior R2.C image/V7 compatibility passed. The30minute observation included actual scheduled GC and an in-window dependency interruption/recovery. Detailed measured values and hashes are in `deploy/r2.2-acceptance.json`.

Actual Docker host is approximately8GiB; combined container limits2528MiB are below2887MiB and sampled host reserve exceeds768MiB. Sampling is not an absolute peak or minimum3.6GiB physical-host certification. Real supplier calls0; cloudR1 unchanged. Historical FAIL/PARTIAL/NOT_RUN entries below are preserved as process history and superseded only by these explicit passing reruns. Public receipt excludes raw logs, browser contexts/form values, SQL, secrets and proof signatures.

Protected two-level promotion, archival and delivery are pending; R2.2 is not yet VERIFIED.

## Historical process records

# Verification
Current status IMPLEMENTING; runtime gates NOT_RUN until measured, source baseline9412d2c. Cloud unchanged, provider calls0.

Intermediate engineering: Maven verify158 tests,0 failures/errors/skips; targeted ChatPostgresTest36 rerun includes historical V6 prefix/empty-history V7 backfill and canonical snapshot preservation; ChatProjectionTest2 passed after the nullable envelope fix. Vue32 tests/typecheck/frozen install/flag-off+on builds/contracts/links and strict14 passed. The real HTTP MVC PoC passed5 cases including8/9 admission, stopped-reader35s bound, revocation and pre-header cursor/oversize errors. These transport tests use stubbed application services; real API DB-pool/browser/image/observation receipts are still required. Logs remain protected under `.cache/r22-*.log`; exact committed source/image binding follows candidate freeze.

Candidate8c1ac78 is provisional: Linux58 passed and real protocol passed (8/9 admission, no held idle transactions, privilege/floor/snapshot assertions). First browser run14/17 passed;3 failed. Preserve initial logs/contexts privately. A watchdog interval overflow under delayed reads and incomplete slow-task test cleanup were identified; update and revalidate before final candidate/observation. No stage promotion or VERIFIED claim follows these partial results.

Correction regression: full Maven verify160 tests with0 failures/errors/skips; Vue34 tests and other engineering gates passed. Backend candidate2973cfc passed58 non-root Linux tests. A subsequent frontend-only exact-query snapshot guard is being verified; its gateway is rebuilt while retaining unchanged backend/probe/provider identities with input-tree equivalence. Real runtime/browser/fault/observation/rollback gates still remain mandatory.
| Gate | Required assertions | Status |
|---|---|---|
| Lifecycle PoC | real8/9streams, backpressure,35s cleanup, DB pool not retained | PASS |
| PG/V7 | fresh/upgrade, snapshot commit/rollback, prefix boundaries/concurrency/rollback/least privilege | PASS: two collectors racing actual application append; prior canonical facts/checkpoints/outbox/inbox hashes unchanged |
| Protocol | all cursor precedence/bounds,410 rebase, Unicode/duplicates/old-generation, privacy | PASS |
| Engineering | Maven verify/DDD/Enforcer,Vue type/test/build, generated diff/invalid fixtures,strict/links/fullstage diff | PASS; final metadata/closure recheck pending |
| Runtime | actualPG/MQ/two workers owner/fence/UNKNOWN, auth/DB/broker failure, Linux old state/Jackson | PASS: Linux58, live revoke/DB, six worker crash windows, real broker/active-DB failure,12 sequential tasks |
| Browser | real login/chat/config/diagnostics, refresh/switch/offline/expiry/dual-user/responsive/keyboard/themes | PASS:18/18 in two rate-window-isolated groups |
| Resources |30min deterministic single-slot with reconnect/GC,2528 initial/2887 max/768 reserve/no swap extension | PARTIAL: admission and gateway pressure passed;30min pending |
| Recovery/delivery | previous R2.C image on V7 with collector disabled, exact artifacts/PRs/archive/protected hashes | Rollback PASS; protected promotion/archive/delivery pending |
Commands use wrapper verify, frontend scripts, openspec validate --all --strict, isolatedr22 Docker acceptance. No source change after final candidate without affected revalidation. Never count skipped tests or setup fixtures as actual provider evidence.

Current combination: gateway9d6bbd6, API/probe/provider2973cfc with unchanged backend inputs. Latest browser groups10+8 passed without changing the20/60 login limiter. Real stream closure samples: membership0.54s, session2.04s, database1.28s; no post-header Problem and no new model calls. Eight connections held14s; ninth503; admission returned about3.54s after close; no idle DB transaction. Raw history (cold-timing, navigation assertion and rate-window failures) remains protected and is superseded only by explicit passing reruns. Stage remains IMPLEMENTING until every pending gate and protected promotion finishes.

Final-candidate runtime progress: six real worker crash windows, broker/active-DB outages and12 sequential single-slot tasks PASS. Previous R2.C API cd879ff on V7 passed deterministic chat/snapshot/replay and the exact R2.2 API was restored. Two real collectors raced append in the same conversation; prior canonical task/message/attempt/model_call/model_turn/checkpoint/budget/outbox/inbox hashes remained identical. Only event age was simulated. The1800second observation is RUNNING; no VERIFIED or promotion claim yet.
