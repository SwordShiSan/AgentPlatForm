# Verification matrix
All functional groups start NOT_RUN. Documentation strict is a pre-code gate, not functional proof.

| Group | Normal/boundary/failure/recovery evidence | Status |
|---|---|---|
| SDK | Real SDK CONTROL schema, ASK, stop, independent JVM restore, exact ConfirmResult; bad state/fixture/Jackson isolation | NOT_RUN |
| Plan | Generate, immutable edit/diff, model revision, text confirmation, rejection, DELIVER_PLAN with zero extra calls | NOT_RUN |
| Questions | Required/options/free text/optional skip; expiry, UTF-8 limits, context overflow preserved | NOT_RUN |
| Ownership | ASK/checkpoint/stop/answer/MQ crashes, two answers, repeated delivery, stale version/fence, revoke/cancel | NOT_RUN |
| Budget | >4 calls/>8 rounds, known refund, UNKNOWN hold, daily user1M/default/override/admin unlimited, cross-window UNKNOWN and concurrency conservation, exhaustion, wait excludes active time | NOT_RUN |
| Database/MQ | Fresh/V14→V15, legacy compatibility, rollback/CAS/outbox confirm/ACK and new-attempt once-only | NOT_RUN |
| UI | Real API, refresh/lost response/conflict, double user, keyboard/themes,375/768/1440 and200%zoom | NOT_RUN |
| Engineering | Maven verify/DDD/persistence guard/Mapper, Vue type/test/build, generated no-diff, illegal fixtures/links/strict | NOT_RUN |
| Real Flash | Only after deterministic PASS; necessary Plan/QUESTION/text continuation, <=20 physical requests including UNKNOWN/retries; no reset | NOT_RUN |
| Closure | Protected two-level PRs, exact source/image/evidence hashes, archive R4.1 only, local cleanup/Skill/body delivery | NOT_RUN |

Resource profile <=2887MiB, host>=768MiB reserve; do not run builds concurrently with limited runtime acceptance or start business sandbox. Provider quota error stops real calls. Failed/skipped tests retain FAIL/NOT_RUN. No R4 production deployment/tag.
