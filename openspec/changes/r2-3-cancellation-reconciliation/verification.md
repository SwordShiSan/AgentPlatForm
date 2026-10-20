# Current measured acceptance conclusion (2026-10-20)

All mandatory functional gates PASS on candidate fe367e575a6f6cbee3b443f08015a40151e8f124: Maven176 (zero skipped), Vue34/type/build/contracts, non-root Linux64, real API browser20/20, actual PG/MQ/two workers, cancellation/ACK/checkpoint races, blocked provider and checkpoint callbacks, signed host stop proof, protected operator accounting/CAS/idempotency/rejections, old V7 image on additiveV8 and restored exact candidate. Runtime baseline1 and V1–V7 remain unchanged.

Observation1801.82seconds/68samples/67tasks included actual in-window broker and active-DB interruptions. Actual Docker VM is about8GiB. Limits≤2887MiB and sampled host reserve≥768MiB; no claim about minimum physical host, absolute peaks or dual-execution capacity. Real supplier calls0; cloudR1 unchanged. UNKNOWN fixture calls and holds are intentionally retained, not silently replayed or zero-filled. Known stopped BLOCKED cancellation requires latest matching receipt; historical missing proof is never inferred.

Evidence/commands/artifact hashes are in deploy/r2.3-acceptance.json and protected local records. Initial stale probe dependency, malformed test request and pre-freeze assumptions remain historical failures/NOT_RUN; explicit final reruns supersede them. The earlier1651second observation stopped at the correctly enforced default fixture budget and is not credited: a finite isolated account limit2000000micros (prior1000000) preserved every usage/UNKNOWN hold and the full1800second gate was restarted. Production defaults remain unchanged. Raw logs, browser forms, SQL, credentials and proof signatures are excluded from public Git.

Protected two-level promotion, archive and delivery remain pending. R2.3 is not yet VERIFIED.

## Historical process records

# Verification
Observation environment correction: the first final run stopped before1800seconds with actual429/QUOTA_EXCEEDED. A separate request verified the exact code and unchanged budget row after rejection. The protected isolated test account limit is configured from1000000 to2000000micros with all prior usage/UNKNOWN holds unchanged; production defaults and application admission remain unchanged. Preserve that failed run and restart the full1800second gate. No partial duration is credited as a completed observation.

Final harness compatibility correction: slow-reader pressure uses the explicitly selected stage BASE port (R2.3 18091), never the R2.2 port18090. Retention cancellation checks an actually confirmed stop and empty slot; a cancelled in-flight unknown call must remain BLOCKED with null usage and RECONCILING reservation, rather than the old R2.2 CANCELLED assumption. Runtime backend/frontend and immutable images remain unchanged; test source/hash is recorded independently. The first wrong-port failure is retained as NOT_RUN pressure evidence, not a platform failure or PASS.

Current implementation update (2026-10-20): the corrected backend passes176 Maven tests, zero failures/errors/skips, including known BLOCKED cancellation, historical missing-proof rejection and the actual SDK/HTTP/PG cancellation pipeline. SDK state, model commit and checkpoint callbacks now enter the same tracked stop barrier as transport/writer/timer work. Final immutable-candidate Linux/container/browser/30minute gates are pending; preliminary dc812ca results below are not final-candidate acceptance. Stage remains IMPLEMENTING.

Status IMPLEMENTING, not VERIFIED. All new runtime assertions NOT_RUN until measured. Baseline7598163, real supplier calls0, cloudR1 unchanged.

2026-10-20 intermediate evidence: Maven verify174/174, skipped0; Vue34unit/typecheck/frozen install/both builds/contracts+invalid fixtures/links PASS; persistence boundary PASS; strict15/15 PASS. Real SDK/HTTP/PG pipeline cancellation and actual blocked teardown tests PASS on host JVM. Container, browser, 30minute and closure gates remain NOT_RUN. Initial Mockito prerequisite, history-count and Vue inference failures were corrected and their logs retained locally. This is not stage completion.
| Gate | Normal/boundary/failure/recovery assertions | Status |
|---|---|---|
| Feasibility | real SDK HTTP stream; cancellation drains callbacks/writes/timers/transport; stuck teardown keeps slot; late callback rejected | NOT_RUN |
| Persistence | fresh/V7→V8/schema1/old migrations; queued/running/terminal races; idempotency/concurrent revision/rollback/space scope | NOT_RUN |
| Ownership | two actual workers, ACK/confirm faults, DB/broker loss, old fence/CAS, proof missing/forged/stale rejection | NOT_RUN |
| Reconciliation | stopped-only; evidence/call/revision match; unknown usage null; usage bounds; zero duplicate refunds/settlements; no replay | NOT_RUN |
| UI | real API dual-user/admin isolation; response loss/refresh/SSE; partial/unknown notice/new independent task;375/768/1440/200%/keyboard/themes | NOT_RUN |
| Engineering | Maven verify/DDD/Enforcer, mapper/XML, Vue type/unit/build, contracts/invalid fixtures/links/strict/persistence guard, non-root SDK/Jackson | NOT_RUN |
| Resources | >=1800s deterministic repeated task/cancel/dependency recovery,2528 initial/2887 cap/768 host reserve, actual host/sample-only claims | NOT_RUN |
| Closure | immutable artifacts, no secrets, two-level protected PRs/archive/ledgers/two protected hashed local copies30days | NOT_RUN |
