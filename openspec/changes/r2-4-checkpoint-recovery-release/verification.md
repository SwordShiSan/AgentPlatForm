# R2.4 verification ledger

Status IMPLEMENTING, not VERIFIED or RELEASED. Approved Plan in conversation; formal body is outside Git. Baseline23f68812c8a8438684653d2ec89fbbef9293fb07. No new supplier call, cloud mutation or capability is claimed by the governance files.

| Gate | Required evidence | Current result |
|---|---|---|
| Governance | baseline tree equivalence; formal Plan/ADR/contracts; single strict-valid change before business code | PASS: remote main23f6881/phase equivalent; scoped branch created; strict valid before business changes |
| SDK feasibility | actual SDK initial/restore/finished-operation/CAS experiments; source signatures and exact fixture identity | PASS: actual SDK2.0.4, non-root Linux68 tests, atomic checkpoint and finished-operation restore |
| Engineering | Maven verify/DDD/Enforcer/MyBatis resources, Vue type/unit/build, generated contracts/invalid fixtures, links, boundary guard, strict, old fixtures/Jackson | PASS on frozen application80f0bd31ec326320b38925fd1612e081a0c3f8bf; exact-source LF archive and build logs retained |
| Data | fresh,V8-to-V9,old R1-generated data upgrade; no V1–V8 change; old image V9 compatibility | PASS: actual R1 image on V9 then same R2 candidate restored; additive V9 only |
| Ownership | real PG/MQ/two worker kills, ACK/confirm/duplicate/DB/broker; stop evidence and stale fences | PASS: inherited actual process/protocol gates and eight exact-container recovery windows |
| Recovery | INPUT_READY/model/tool/terminal checkpoints, immutable independent state, stable identities and side-effect count | PASS: eight final-image windows; shared effect count1; unknown/advanced states refused without replay |
| Refusal | hash/state/bundle/codec/watermark/newer-task/UNKNOWN/cancel/revocation/deadline/budget/concurrent-command rejection | PASS: repository tests, exact-process windows and ten actual V2 integrity/codec/quota/cancel/CAS probe cases |
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
- Container iteration457fe4a passed INPUT_READY, PREPARED tool and committed-tool windows. Live strict SSE replay then reproduced an undeclared recoveryRevision payload field. The producer now preserves the frozen event shape and a real-PG assertion covers this regression; previous container results are historical, not final-candidate acceptance. A later window was refused by the exact-backend-tree guard after the fix, before mutating a worker. The browser harness initially omitted an API-path-scoped Cookie; correcting the harness reproduced the actual schema failure, rather than treating401 as contract evidence.

## Frozen application progress, 2026-10-21

- Application source80f0bd31ec326320b38925fd1612e081a0c3f8bf, backend tree dee6f5c6213672742a5c80e728a2eaa98aa70611. Later acceptance-only scripts and ledger commits do not silently change the accepted application trees. Image/export/config identities are retained in the protected final-candidate and image-chain records.
- All eight real-container windows passed: input, prepared-tool, committed-tool, terminal, unknown-model, advanced-round, unknown-tool and advanced-tool. Stops use exact identities and natural lease expiry, without extending the task-wide deadline. Unknown and advanced refusal assertions check the exact error code.
- Inherited actual PG/MQ/process gates passed: claim/ACK boundaries, dependency outages, replay/cancellation suites, and the released R1 image on V9. The old image is resolved through its verified local OCI ID and export-chain cloud config ID, not through a mutable tag.
- The additional non-root trusted integrity probe passes ten actual V2 cases, including hash-valid invalid codec/scope, incompatible bundle, missing state, watermark, insufficient budget, cancellation and competing recovery commands. JDBC is restricted to isolated test corruption injection; production paths use the accepted MyBatis/Spring repository. Synthetic NO_EXECUTOR_LAUNCHED fixture receipts are explicitly not container-stop evidence. Combined dependency/probe caps and host reserve are checked before launch.
- Probe harness history: the first invocation supplied provision arguments in the wrong order and failed before creating its source task; corrected harness passed twice. This is retained as harness failure, not a product defect. No historical usage, quota counter or UNKNOWN reservation was reset.
- Browser, final1800second observation, minimal real-supplier call, local PR promotion and cloud release remain pending until their own reports pass. No real supplier request has been made in this stage yet. Existing browser inventory contains no logged-in cloud console session; no cloud configuration was changed.
