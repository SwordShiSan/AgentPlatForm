# Verification
Status: local mandatory gates PASS, two-level promotion pending; cloud gates NOT_RUN. R1.4 is not yet VERIFIED and R1 is not RELEASED.

Record command, exit code, source, images/config IDs, migration version, environment, assertions and evidence SHA256 for each case. Separate deterministic, real dependencies, real provider, browser, local resource and cloud evidence. Retain old failures and NOT_RUN.

| Group | Cases | Required evidence |
|---|---|---|
| Governance | remote refs, Actions off, clean baseline, five artifacts strict | captured refs, strict exit0 |
| Trace/API | request Problem/log match; submit/outbox/attempt/call chain; cross-user/admin denial; sanitized diagnostics | contract/client check, unit/PG/HTTP assertions |
| Accounting | old thresholds surpassed without reset; business budget rejects; UNKNOWN holds; balance vs429 classification | isolated DB tests and historical application meter before/after |
| Authorization | all resources, disable/password/membership/key/model changes before/during execution | real PG/MQ and dual-browser cases |
| Execution | idempotency/rollback/confirm/ACK/fence/leases; DB/broker interruption; killed worker stopped-owner reconciliation | no direct execution or unknown replay |
| Engineering | wrapper verify/DDD/Enforcer, Vue types/unit/build, contracts no diff/invalid, links, strict, Linux SDK/Jackson | no required skipped tests |
| UX | drafts/session/refresh/SSE/cancel/errors,375/768/1440,200%,keyboard,light/dark | real API browser screenshots and assertions |
| Real model | Flash/Pro PLATFORM/USER and multi-round minimum; cloud minimal smoke | cumulative call/usage facts, stop on remote quota |
| Cloud | 3655MiB/4CPU/40GiB;caps<=2887MiB;reserve>=768MiB;disk>=5GiB;serial runtime | preflight and >=30min sampled observation, no absolute peak claim |
| Restore | stopped-write PG/objects/workspace/MQ/key version backup; independent serial restore; R0 rollback then R1 | data/hash/quota/decrypt/readiness and immutable identities |
| Handoff | two-level PRs, tag after cloud PASS, archive and protected hashes>=30days | local/cloud evidence copies and separate canonical-body receipt |

## Local evidence2026-10-17
- Runtime/API/worker/UI/fixture source c37bec9fdb34bced93b460918d80752107e2b3cc; main-source content matches the accepted working snapshot. V5 migration from actual V4 succeeds; V1–V4 unmodified, baseline schema1 retained.
- Full Maven install121 PASS/0errors/0failures/0skips; then affected identity26 PASS including new stable-ID rename-storage invariant,122 distinct latest tests. This is not reported as a full122-test command.
- Non-root Linux SDK/Jackson/provider/crypto/projection52 PASS; actual Linux PG state/CAS3 PASS; Vue20/typecheck/build/frozen lock PASS; contracts no generation drift/invalid fixtures,21links,8-file freeze and Docker preflight5 PASS.
- Browser14 PASS on real API/PG/MQ and deterministic provider,375/768/1440/zoom200%/themes, credential non-echo, dual-user/admin isolation, partial UNKNOWN diagnostic and copy feedback. Dark/light fault-card captures in frontend/tests/ui-evidence/r1.4-diagnostics-*.png, visually inspected.
- Four real successful Flash PLATFORM→Pro PLATFORM→Pro USER→Flash USER turns in one conversation. Cumulative meter13→17, old UNKNOWN reserve49152 unchanged, estimate5474→7434micros, not invoice. Transient USER acceptance key revoked and fixture bindings restored. No fixed acceptance ceiling; business budget still enforced.
- Eight deterministic provider cases including402 (one request, no retry),429/5xx/empty repair/partial/truncation/missing usage/timeout. MQ/DB outage and active DB outage/stopped-owner reconciliation PASS;24serial tasks with login overlap and actual64MiB worker OOM/512MiB recovery PASS, UNKNOWN not replayed.
- Local caps2016MiB including fixture, zero current container swap; cloud render10 resident services2272MiB,<=2887MiB. Local Desktop is larger than the target host, no cloud-capacity or absolute-peak claim.
- Offline credential verification authenticates all ciphertext records without provider calls; source/runtime logs/full DB including SDK state/events secret/sentinel scans PASS; exact JAR/image/source hashes captured.
- Retained failed history: sandbox loopback denial; missing old DB acceptance-ceiling removal fixed in V5; isolated identity stale V5 checksum and MQ-recreation test race fixed by fresh named fixture and stable MQ; cancellation browser locator ambiguity fixed without weakening functional assertion. Later passing evidence supersedes failures, not erased.
- Protected local package: evidence/r1.4/r1.4-local-acceptance-20261017.zip; SHA256 896ece2c4a569ee5b2a292c3e865235fe9c4bd006f97b309731f3af98ccdeb44; immutable capture status LOCAL_GATES_PASS_PENDING_PROMOTION. Source/logs/results/command exit codes/image identities and file hashes in its index. Retain>=2026-11-17. No SQL dumps, credentials or master keys included.

## Still required
Two-level exact-source PRs, cloud immutable transfer/admission, R0 consistent backup, additive migration/grants/fresh accounts, HTTP browser/minimal model smoke,>=30min real cloud observation, independent serial restore/key/quota/hash/readiness, actual R0 rollback/same R1 final startup, release tag, archive and dual-location evidence/body handoff remain NOT_RUN. No cloud modification has occurred yet.

## Public HTTP candidate failure and repair2026-10-17
First cloud candidate e6c40bf failed browser first-send before creating a conversation: public HTTP lacks crypto.randomUUID, unlike trusted localhost. R0 rollback completed and public HTTP/readiness passed12services; no R1 production tag created. The original accepted local package and failed cloud browser log remain history. Same approved HTTP plan/change applies; no HTTPS or dependency/source upgrade.
Repair is a project-owned UUIDv4 helper using HTTP-compatible crypto.getRandomValues, applied to submit/cancel; deterministic capability-absence/browser tests plus actual public-HTTP capability check are required. Backend c37bec9 and its121/26/52/PG3/provider/real4/resource evidence remain unchanged; rebuild only gateway, run frontend/types/build/unit/browser and contracts/strict, then repeat two-level PR before deploying the corrected immutable gateway. Do not repeat real four-turn local calls or reset metering. Final cloud30min/restore/rollback still NOT_RUN.
References: https://developer.mozilla.org/en-US/docs/Web/API/Crypto/getRandomValues and https://developer.mozilla.org/en-US/docs/Web/API/Crypto/randomUUID . Export identity correction: Docker Desktop local image IDs are OCI index digests; cloud uses config IDs. Preserve index→amd64 manifest→config→rootfs proof instead of equating them.

HTTP repair local PASS: Vue22/types/build, browser15 (randomUUID/clipboard absent), secrets/identities, unchanged backend evidence reused; no additional real calls. UI source 6a4014b4adfff4c32db70387be14666fa93de581, API source c37bec9fdb34bced93b460918d80752107e2b3cc. Supplement SHA256 9927da2adbd575ccbe3d087a4947a1ea44698c1eb7d130ac1abe303edf336cad; original896ece package retained as initial candidate. New two-level promotion pending; cloud fixed candidate NOT_RUN.
