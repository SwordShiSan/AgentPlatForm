# Verification — R4.1

Current status: VERIFIED. Production remains R3; R4 is not RELEASED. Source/image/report hashes: `evidence/r41/acceptance-index.json`. Historical FAIL logs are retained, never relabeled PASS.

| Group | Evidence and scope | Current result |
|---|---|---|
| SDK | Actual AgentScope typed CONTROL/ASK, independent-JVM restore, exact ConfirmResult, old fixtures and Jackson isolation; full Maven regression and SDK probe logs | PASS |
| Plan | Immutable edits/stale confirmation, model revision/rejection/new review, text-only same-task continuation, plan-only zero extra request | PASS |
| Questions | Required/free text/options, optional next-safe-point once-only consumption, expiry/cancel, UTF-8 validation | PASS |
| Ownership/failures | Control intent and WAITING checkpoint failures cannot become resumable waits; claimed-answer stop/new binding without model replay; late/stale answers rejected | PASS |
| Budget | Main PG/MQ13 plus concurrency/review/waiting increments (16 distinct tests), >4 requests/>8 rounds, daily default/override/admin-role revocation, UNKNOWN across midnight/late settlement, independent acceptance20counter, cumulative active time | PASS |
| PG/MQ | Fresh/V14→V15, actual outbox/confirm-result-loss/channel close before ACK/redelivery/duplicate handoff single owner; first teardown error fixed in test cleanup only | PASS |
| UI | Real API browser3, immutable edit/refresh, QUESTION, plan-only, same-task continuation, double context/admin isolation,375/768/1440,200%zoom,keyboard/light/dark; Vue44/type/build | PASS |
| Engineering | Full Maven verify/DDD/Enforcer/Mapper, persistence guard, generated contract no diff/invalid fixtures, links and strict | PASS |
| Real Flash | First request balance rejection retained; recharge confirmed through one official read-only balance check; same runId final5/20,4 successful requests,4508 reported tokens,0UNKNOWN; QUESTION/Plan/approval/text continuation same task | PASS |
| Closure | Implementation PR108/109 merged; this change archived; Skill and cleanup complete; protected delivery is sealed before closure publication | PASS |

Real cost0.0106CNY is an estimate from frozen prices, not a supplier billing receipt. The first balance rejection retains null usage; no reset or alternate key. Acceptance credential revoked and all local fixture services stopped; volumes/images retained. No R4 deployment/tag or R4.2 start.

Unchanged historical database suites and a full new two-OS-worker exhaustive crash matrix are NOT_RUN in this invocation, not falsely passed by skipped Maven classes. R4.1's changed boundaries are covered by the listed SDK, transaction, real broker redelivery and new-attempt fault tests; the unchanged R2 infrastructure's historical scope remains separate. No minimum3655MiB physical-host or model-throughput capacity claim.
