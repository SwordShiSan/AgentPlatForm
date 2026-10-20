# R2.4 verification ledger

Status IMPLEMENTING, not VERIFIED or RELEASED. Approved Plan in conversation; formal body is outside Git. Baseline23f68812c8a8438684653d2ec89fbbef9293fb07. No new supplier call, cloud mutation or capability is claimed by the governance files.

| Gate | Required evidence | Current result |
|---|---|---|
| Governance | baseline tree equivalence; formal Plan/ADR/contracts; single strict-valid change before business code | PASS: remote main23f6881/phase equivalent; scoped branch created; strict valid before business changes |
| SDK feasibility | actual SDK initial/restore/finished-operation/CAS experiments; source signatures and exact fixture identity | NOT_RUN |
| Engineering | Maven verify/DDD/Enforcer/MyBatis resources, Vue type/unit/build, generated contracts/invalid fixtures, links, boundary guard, strict, old fixtures/Jackson | NOT_RUN |
| Data | fresh,V8-to-V9,old R1-generated data upgrade; no V1–V8 change; old image V9 compatibility | NOT_RUN |
| Ownership | real PG/MQ/two worker kills, ACK/confirm/duplicate/DB/broker; stop evidence and stale fences | NOT_RUN |
| Recovery | INPUT_READY/model/tool/terminal checkpoints, immutable independent state, stable identities and side-effect count | NOT_RUN |
| Refusal | hash/state/bundle/codec/watermark/newer-task/UNKNOWN/cancel/revocation/deadline/budget/concurrent-command rejection | NOT_RUN |
| UI | live API, dual user/admin isolation, read-only recovery stages, partial/refresh/SSE, widths/zoom/keyboard/themes | NOT_RUN |
| Real model | minimal frozen-input restart and committed-final no-replay; meter existing holds, stop at provider quota | NOT_RUN |
| Resources | final frozen candidate>=1800seconds with dependencies failing/restoring, transient resource limits included, no swap extension | NOT_RUN |
| Local promotion | exact source/config/image/export/evidence hashes; protected two-level PRs; Actions disabled | NOT_RUN |
| Cloud | verified R1 identity/capacity, immutable deploy/migrations/permissions, HTTP/smoke/observation, serial consistent restore and R1 rollback | NOT_RUN |
| Delivery | local and cloud protected evidence; separate body copies/SHA256/retention>=30days; local stack explicitly stopped | NOT_RUN |

Tests use isolated agentplatform-r24-local,15447/15697/15687/18092, initial2528MiB/max2887MiB plus host reserve768MiB. Actual Docker VM about8GiB; no minimum-physical-host or double-execution capacity claim. Every command records source/script/artifact identity, exit code and assertions. Failure and NOT_RUN stay visible. Raw SQL, keys/passwords, host proof signatures and unsanitized browser logs are protected and excluded from Git.

## Implementation evidence, 2026-10-20 (intermediate, not final acceptance)

- Pinned SDK experiment:32 tests PASS before runtime wiring; explicit clone leaves active SDK state unchanged, independent attempt restore does not duplicate user input. Historical result, not final-image evidence.
- Real PostgreSQL RecoveryPostgresTest:14 cases PASS, including actual SDK continuation, PREPARED tool execution, SUCCEEDED tool skip, independent state namespace, task/deadline identity, atomic CAS rollback, command idempotency/version refusal, advanced ledger, unknown call retention, membership isolation and restricted API role denial of SDK state/manifest/ciphertext.
- Existing real ChatPostgresTest44 cases and ownership17 cases passed. Full Maven verify, Vue34 tests/type/build in both feature modes, frozen lockfile, generated/invalid contracts, links, persistence boundary and OpenSpec16 strict items passed on the implementation iteration. Subsequent profile-only changes are rechecked before image freeze; these results do not assert exact final candidate acceptance.
- Failure history retained in protected logs: initial old test migration stopped at V8; recovery dispatch used the wrong owner column; an added bundle key was correctly rejected by HC-1 composition validation. Fixes preserve V1–V8 and the existing five-component bundle shape. No failing iteration is described as a release pass.
- Current cloud prerequisite: SSH closes both directly and through the existing local proxy. No cloud mutation was attempted. Immutable-image/two-process/browser/final observation/real-supplier/PR/cloud/restore/delivery gates remain NOT_RUN.
- Protected working evidence: `.cache/r24-*.log` and `.cache/r24-local`; source and artifact hashes will be indexed after freezing. No credentials, raw SQL or unsanitized logs are added to Git.
