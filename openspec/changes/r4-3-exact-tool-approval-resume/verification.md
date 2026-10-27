# Status IMPLEMENTING — no functional gate passed yet
Record exact source/image/command/exit/assertions/SHA256. Preserve FAIL/NOT_RUN.
- SDK actual2.0.4: ASK→termination→JVM exit→exact original call allow/deny; empty rules, no replacement reasoning before operation, unsupported batches execute0.
- Engineering: Maven verify/DDD/Enforcer/Mapper/XML, frontend type/test/build, contracts regenerate-no-diff/invalid fixtures, links/strict. New install/V16→V17, old fixture/Jackson.
- Real PG/MQ: atomic answers/consumption, concurrency/idempotency, duplicate delivery/confirm/ACK loss, two consumers, permission/fence/lease and stale target races.
- Crash: intent/ASK/checkpoint/stop, answer/outbox/ACK, restore before consumption, RUNNING before effect, effect before result, result/checkpoint. Count effects and inspect canonical/holds; UNKNOWN never replays.
- DENIED persistence/history/checkpoint, continuation, repeated same fingerprint suppression, expiry/cancel/stale/forged identity. Admin quota bypass is not ACL bypass.
- Browser actual API: approval refresh/lost response/two tabs, allow/deny/stale/unknown, Plan/Question regression,375/768/1440/200%/keyboard/light-dark.
- Daily default configurable1M/admin unlimited metering, no physical-request ceiling,120s active time/unknown holds unchanged by waiting.
- Resources2080/2720MiB sampling, isolated project, operator/build serial. No supplier calls/cloud deploy/sandbox physical reruns; no new30min observation requirement. Stop all fixture/app/dependency containers, retain images/volumes.
