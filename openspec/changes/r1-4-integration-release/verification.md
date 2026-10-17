# Verification
Status: NOT_RUN. No implementation or release is claimed by this specification.

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
