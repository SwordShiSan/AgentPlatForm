# Verification
Status IMPLEMENTING. Approved baseline ee741e3; final candidate/whole-stage acceptance has not completed. Partial development results below do not mark any mandatory group PASS.

| Group | Mandatory normal/boundary/failure/recovery assertions | State |
|---|---|---|
| Engineering | Maven/DDD/Enforcer, Vue type/test/build, schemas/invalid fixtures/generation, strict/links, R1.1/old state/Jackson | NOT_RUN |
| PG/MQ | Submission rollback/idempotency races, actual confirm/ACK loss, inbox transaction rollback, old generation/fence, queue order, single slot, broker/DB outage recovery, no DB execute bypass | NOT_RUN |
| Budget | Concurrent reservation, per-call unique settlement, unknown usage/retry accounting, real cap20/1CNY across restarts | NOT_RUN |
| Streaming | Unicode split offsets, committed batch before final, snapshot consistency, reconnect/duplicates/gaps, bounded buffers, no hidden reasoning | NOT_RUN |
| Provider | Empty/truncation/429/5xx/missing usage/after-output disconnect/timeout/cancel; real DeepSeek stream/history/usage | NOT_RUN |
| Authorization | Two users all list/detail/submit/SSE/cancel/usage; current revoke/disable/CSRF; encrypted secret/AAD/foreign reference rejection | NOT_RUN |
| Resources | Total<=2887MiB, one execution/hash slot, login+chat overlap, sequential tasks, bounded memory/cancel cleanup/OOM/swap, no build overlap | NOT_RUN |
| Delivery | Accurate SHA/image/command/assertion/hash, no real key/traces/backups in public repo, PR protection/Actions off, R0 unchanged | NOT_RUN |

R1.3 full configuration UI and R2 full checkpoint recovery remain NOT_RUN; no tools/Plan/files/shell. Real key source is private operator configuration only, never recorded here. Evidence packages explicitly select safe artifacts, not whole fixture directories.

## 2026-10-16 development checkpoints (uncommitted source; not final acceptance)

- Approved Plan/ADR and credential-scope steering synchronized; change strict PASS before implementation and compatibility fixes.
- Maven verify passed at the earlier development snapshot. After subsequent Harness bundle/CAS fixes,18 real PostgreSQL/Harness/MQ tests PASS (zero skipped), including actual unacknowledged channel redelivery and publication without application-observed confirm; final full regression remains required.
- CredentialCipher4 tests PASS; provider transport2 tests PASS (local HTTP fixture, no real DeepSeek calls); Vue18 unit tests/typecheck/build PASS.
- Docker preflight5 unit tests and live engine check PASS; engine desktop-linux/Linux28.5.1 already running. Execution permission failure is distinct from Desktop startup, and preflight never restarts Desktop.
- Isolated project agentplatform-r12-local/PostgreSQL15442 initialized. chat_test is the explicitly guarded destructive test database; chat is the separate application database. V1/V2 remain unchanged, V3 migrated in local development only.
- API metadata-only credential SQL grant, USER-owner rejection/admin non-bypass, PLATFORM explicit binding, frozen-source revoke/no fallback, idempotent submission, unknown-usage reservation, old fence, unsent-call release and event expiry tested in PostgreSQL. Runtime API/MQ/browser/complete outage and resource evidence still pending.
- Development issues found and corrected: negative private-state CAS sentinel incompatible with PostgreSQL, scripted-only composition gate, unused-call reservation retention, and SDK retry bypass risk. Historical local failure logs remain under ignored .cache; they are not green acceptance evidence.
- Actual provider key not imported; real model requests0. No cloud modification, GitHub Actions invocation, PR promotion or production tag.

## Resumed development checks (not final candidate acceptance)

- Explicit SSE MIME and servlet flush fixed observed25s buffering. Actual gateway first headers/frame arrived in0.191s; three real browser tests PASS (stream/history/themes/mobile, running partial/cancel, two-context isolation). New reconnect/reverse-admin cases still require execution.
- Revoked queued actors and stopped-owner recovery now commit assistant terminal events alongside task failure; real PG tests cover partial preservation, unknown call reservation and no replay.
- Initial AMQP fault fixture assertions passed but cleanup attempted to close an already aborted channel; corrected fixture cleanup. A later run overlapped broker startup and failed handshake. Fully healthy serial rerun18/18 PASS; historical failures remain local evidence.
- Deterministic429 caught SDK-hidden HTTP retries (3 physical requests/1 ledger row). Explicit ExecutionConfig no-retry HTTP tests PASS, but the first Docker rerun still embedded an older packaged jar. Build now rejects stale artifacts; full repackaging/reacceptance is in progress. These failures are not accepted model/budget evidence.
- R1.1 PG regression attempt did not run because required Compose image variables were omitted, leaving15441 unavailable. Fix fixture invocation and rerun; do not count it as regression PASS.

Latest development results: R1.1 real PG24/24 PASS after fixture correction; chat real PG/MQ22/22 PASS; full Maven verify PASS with newly added createdAt/id paging test (23 cases,2 MQ explicitly skipped because broker stopped; previous separate actual MQ evidence retained). Vue frozen install/typecheck/18 tests/build/contract fixtures/links PASS. Deterministic seven provider failure scenarios PASS, including timeout120s and exact HTTP/ledger count, prior to the follow-up partial-transport UNKNOWN classification fix; rerun that case on final image. Broker/DB outage recovery PASS. Final resource/OOM/reconnect/real-provider/immutable-candidate/promotion remain pending. No paid calls yet.
