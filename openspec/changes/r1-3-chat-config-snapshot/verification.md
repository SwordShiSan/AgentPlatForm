# Verification
Initial state NOT_RUN; no historical R1.2 PASS substitutes for new verification.
- ENG: Maven verify/DDD/Enforcer, Vue types/unit/build, contract regeneration/invalid fixtures, links, OpenSpec strict.
- DB: clean V4 and V3 upgrade, atomic first task rollback, concurrent exact-key replay/body mismatch, preferences 412, frozen snapshot after defaults/model/source changes.
- SEC: two users and admin denial, forged owner/source rejected, ciphertext only, nonce uniqueness, old-key revocation and no source fallback; sentinel scan source/logs/DB/events/checkpoints/browser/evidence.
- EXEC: R1.2 PG/MQ/lease/fence/UNKNOWN/usage regression; deterministic two models, failure/empty/partial/timeout and capability mismatch.
- UI: drafts refresh and isolation, uncertain submit replay, key no echo/storage, config display, 375/768/1440, 200% zoom, keyboard, themes, offline/403/404/long text.
- REAL: Flash and Pro, consecutive model changes and PLATFORM/USER source, existing meter <=20 requests/1CNY including old UNKNOWN reservations; initial count expected four, verify before any call.
- RES: total <=2887MiB, no swap extension, single execution/hash slot, login overlap and repeated tasks/cancellation release; sampled memory is not an absolute peak claim.
- DELIVERY: source/image/commands/hash evidence, step→phase→main review, protected local evidence/body receipt, archive and ledger. Cloud R0 unchanged, no production tag.

## 2026-10-17 functional acceptance
All functional cases PASS; promotion/archive remain pending and do not borrow a production release claim.
- ENG: reactor 113 tests, zero failures/skips; affected final backend rerun48; Vue20/typecheck/build/frozen install; contracts/invalid fixtures/links/strict PASS. Final full reactor is repeated before promotion; backend tree167a37cc56eec2e10edc6843fbe726124cb45ef1 matches the runtime source.
- DB/SEC: staged V1→V4 and clean migration, initial rollback/concurrent exact-key replay, preference412, owner/admin isolation, key replacement/revoke, immutable config/prices; API ciphertext SELECT denied.
- LINUX: nonroot48, real PostgreSQL state/CAS3, zero skipped. SDK stream request wire covers Flash/Pro, duplicate JSON keys rejected, reasoning/control filtered.
- UI: final real API browser13 PASS; 375/768/1440, 200% zoom, keyboard/themes, draft refresh and response-loss replay, key secrecy and source preservation. Safe fixture screenshots committed.
- REAL: one diagnostic Flash succeeded; Pro admission correctly rejected against the original actor's accumulated failure-test holds. A separate non-admin fixture ran four successful Flash/Pro×PLATFORM/USER turns with history/usage, then USER key revoked and fixture restored. Shared meter9 calls,49152micros held,3514micros estimated consumed; no old budgets reset, no invoice claim.
- FAILURES/RES: seven provider cases, broker/idle DB/active DB recovery,24 serial tasks plus login/cancel, actual64MiB cgroup OOM→512MiB restored→UNKNOWN/RECONCILING/no replay→next task success. Caps1760MiB incl fixture, no swap; larger Desktop is not a3655MiB cloud capacity proof.
- SECRET: source/runtime logs/full chat DB including private state/events and credentials PASS, JAR identity matched, real committed streams included multiple batches. No raw SQL saved. Protected package SHA256 d8becff64d1fc9214151d53d2f1f80343f1bf042c1f2b17b2fa6cab1a2f03e4a.
Resolved project issues: reservation column name, leftover Flash assertion, test-vhost persistence, draft loading/source preservation, same-transaction message order, response-loss test synchronization, and empty-stdin Compose operator wait. Vendor sources unchanged.
Runtime API/worker source78d861a; UI f9e697a; sanitized capture4e51c76. Later evidence/docs commits do not change runtime bytes. See deploy/r1.3-acceptance.json and protected index for full hashes and commands. Cloud untouched, Actions disabled, R1.4 production/business-budget separation remains future work.

## Current resource gate: REVALIDATING (2026-10-17)
The earlier1760MiB package is a historical pre-promotion capture. RabbitMQ subsequently exited137/OOMKilled=true at256MiB, invalidating that resource candidate. Preserve proof r13-mq-oom-evidence.json and failed final-Maven log. Raise only the allowed local MQ allocation to512MiB, total2016; preserve hostname/volume/image. Full regression, repeat dependency/resource suite and ten-minute capped observation must PASS before promotion. No vendor rebuild, budget reset or cloud change.
