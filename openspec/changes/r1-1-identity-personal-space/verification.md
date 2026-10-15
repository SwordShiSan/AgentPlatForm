# Verification

Status: LOCAL_ACCEPTANCE_PASS; PR promotion and archival pending. Runtime source d1f27524ca7c7707e6a07a67b440cfd0298fa8a1; acceptance harness 8297b8ce8ab6ed94099b8df00fd9ba808e74da8b differs only in explicit baseURL for isolated browser contexts. Subsequent record changes do not rebuild deployed/runtime code. Cloud remains R0.

| Group | Assertions | State |
|---|---|---|
| Engineering | Maven verify/DDD/Enforcer 69 cases; Vue 15 tests/typecheck/build; generated/invalid contracts; links; strict | PASS |
| Provisioning | Fresh migration via local migration container; V1→V2 via Flyway test; V1 unchanged; atomic rollback at space/membership/storage/operation inserts; duplicate request/name/bootstrap | PASS |
| Authentication | 24 real PostgreSQL identity cases include Argon2 m65536/t3/p1, bounded slot, credential/lock/expiry/rotation/revocation | PASS |
| Security | Null/foreign Origin, missing/wrong CSRF, extra login body, current-user/admin/foreign-space boundaries; runtime user tables remain read-only | PASS |
| Recovery | API container restart; actual PG stop/503 while health stays 200; PG start and same session recovery; real operator reset/logout; original R0 adapter transaction/CAS 3 cases | PASS |
| Browser | Six real-API Playwright cases including baseline accessibility/contrast, actual API restart, two contexts, refresh/logout/expiry, 375/768/1440 light/dark | PASS |
| Delivery | Local evidence/hash and production read-only health200/readiness200/identity404 verified; protected PR22 opened, remaining promotion/archive | PENDING |

Conversation/task/file/event endpoint isolation remains NOT_RUN until those endpoints exist in later steps; R1.1 verifies current-user/space facts and the real repository authorizer only.

## Evidence and commands

Protected local package: `evidence/r1.1/r1.1-local-acceptance-20261015.tgz`, SHA256 `30b383413cf29d7d7facba9a2e48d3403670c0ce9a0ff367b1712274d34f539b`; 38 explicitly selected files plus index. Index binds runtime/acceptance source, running image identities, embedded/local JAR SHA256, commands, 72 JUnit results (69 normal + 3 explicit R0 PG), screenshots and reports. No passwords, raw SQL backups, cookie values or browser traces are packaged. Credentials and aborted-overlap diagnostics remain only in the protected local fixture directory. No cloud evidence copy is claimed for this local-only step.

API inspected image ID `sha256:bd937070587f9ddef5cd3a00fd04ff7b98a6ecc6a994715ed94051f5a8776f47`; gateway inspected image ID `sha256:4095dce20947c74d7b3c009bf492c50653d5a9e59a2bff3a444977fa9ec7b702`. `images.json` and container records distinguish inspected image identities and actual running identity; both OCI source labels point at d1f2752.

Commands: Maven Wrapper `verify` with R11_TEST_JDBC_URL pointing only at loopback 15441/identity_test and R11_TEST_PASSWORD_FILE; explicit `PostgresStateStorageIT`; pnpm typecheck/test/build/contracts:check/links:check; `identity-local.py prepare/build/migrate/grant/seed/start`; `test-identity-http.py`; evidence-bound resource-command.py; Playwright with BASE_URL localhost18086, R11_IDENTITY=1 and exact local API container; `openspec validate --all --strict`. Exit codes 0 for final runs. Frozen Maven resolution: 132 artifacts, two added dependencies (security-crypto 7.0.7 and bcprov 1.83), six dependency trees/effective POMs retained locally.

## Resource observation and historical failures

Actual API limits: 320MiB, 0.75 CPU, 128 PIDs. Six sequential localhost login samples: 0.818–1.934 seconds; two simultaneous requests yielded 200/429, platform alive, OOM false. Five memory samples reached 313MiB: close to the limit, not an absolute peak, cloud-host measurement or higher-concurrency promise. Keep one hash slot and remeasure with chat at R1 release; do not weaken Argon2 parameters.

Historical failures retained separately: missing Nginx temp paths in read-only local config; SELECT FOR UPDATE required excessive user permissions, replaced with shared per-user advisory transaction lock; one browser run overlapped destructive HTTP tests and was aborted. The final HTTP/resource/browser pipeline ran serially and all passed. Runtime authentication does not receive offline operator secrets. Public/cloud identity rollout and its transport remain NOT_RUN.
