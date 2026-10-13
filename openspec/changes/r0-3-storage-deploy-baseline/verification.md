# R0.3 Verification — CLOUD GATES PASS, PR PROMOTION PENDING

## Implementation evidence (2026-10-13, pending PR)

- OPS01: cloud inventory20261013T110947Z, protected fresh MySQL/PG SQL backups20261013T110953Z; gzip/definition/checksum verification. Exact obsolete application images and native log retention reclaimed10637934592bytes. All referenced volumes, current6.133/4.79, rollback6.132/4.78, DB/user files and unproved historical packages retained. No global prune.
- OPS02: first filer startup failure automatically stopped new projects/restored all10legacy containers and originalalways policies; oldvirtual-hostHTTP and backendreadiness200. Current final acceptance window still active, final restore pending.
- DB01:42hostJUnit(29SDK/infrastructure+13app),0failed/skipped; actual cloud PostgresStateStorageIT3/3 includes concurrent one-winner CAS, sameuser/session crossspace, JSONUnicode, malformedJSON, framework mismatch rejection, rollback and deniedAPI-DDL. FlywayV1 independentpf/pf_runtime; filer weed schema/role separate.
- MQ01: mandatory-unroutable rejection and actual Basic.Ack frame interception/connection loss, sameeventId resend, failedhandoff rollback+nack, duplicateinbox/fence/generation checks PASS. No production R2 scheduler claim.
- OBJ01/CACHE01: private bucket admin-only creation; limitedwriter used for PUT/GET/HEAD/Range, ownSHA256,6MiBmultipart/ETag-not-SHA256, orphan DBrollback; master/volume/filer/S3/Redis restart readback/hash PASS. Broker128MBalarm corrected to192MB inside unchanged256MiBcap; no alarm/confirm failure after fix.
- HTTP01: actual cloud schemaVersion2 and Redisdown cause readiness503/NOT_READY; liveness200/UP, restore200/READY. Internal8081health/metrics enabled onlyAPI; public/API metrics path404. Normal/invalid/OpenAPIgeneration and realAPIcontractcheck PASS.
- SEC01/FS01/LIMIT01/IDLE01: realrunsc20261005.0systrap, fixedcompletepackageSHA512;9host acceptance groups PASS:Python/Node/file/rebuild, root/socket/metadata-network/proc isolation, actualnative/guestprojectquota boundedENOSPC byte/inode denial with globalfree/projectreport, nativePIDexhaustionwithoutOOM, memoryexhaustionplatformsurvives,CPU1/killedtimeout,single-slot/lowmem,idle60skeepsfiles,XFSUUID/prjquota/remountpersists.12actualnewservices privilege/resource/log/networkscan PASS. Production signed/fenced command and approval integration staysR3-gated.
- UI01: actualcloudUI4/4(stop/start/offline included), plus finalcandidate3normal/offline/layoutcases and375/768/1440light/dark/keyboard/200%zoom/44pxtarget/WCAG-AA4.5contrast; screenshots visuallychecked. Failedfirstrestarttrace preserved and fixedbywaitingactualHTTP200.
- REG01: finalcloudnetworknone/nonroot/read-onlySDK/JVMregression38/38,0skips/failures. OpenSpec4/4strict; repository19links/13externalreferences and85canonicalMarkdownfiles checked. PublicbusinessAPI/events unchanged exceptminimalreadiness;SDK/shellfeatureflagsfalse.
- SOAK01: fixedfinalcandidate started2026-10-13T21:29Asia/Shanghai, completed1812.649557seconds/55singletrustedlightweightPythonjobs/55samples. Redis/S3down503/recovery both passed. MinimumsampledMemAvailable1688.5MiB; swapinitial166.1MiB/final153.4MiB/maxsampled171.8MiB. No hostOOM or failed job. Sampled statistics are not a throughput or absolutepeak-memory guarantee; sandbox hard-pressure evidence is separate.
- Final OPS02: currentfixturecleanup PASS, allnewservices stopped; all10originalcontainer identities/running/always policies restored; oldvirtual-hostHTTP200/backendreadiness200. Oldbusinessdata and rollbackimages preserved; no production newtag.
- GIT01: stepbranchbasea6d7d5d; noPRpromotion/archive before finalgates. Canonical正文 remainsoutsideGit/local-only.

Failure evidence preserved in ignored `evidence/r0.3`: filer-createTable, memoryalarm, XFS errno assumption, PythonforkmaskingPID, volume-slotgrowth/listener and readiness-order, UIrestartwait and WindowsDockerindex/configidentity. Corrections are requirements-preserving and actualfailure paths remain visible.

Plan approved before implementation. Required cloud gates now passed; documentation or mock tests never substitute for real-host gates. Record command, exit, fixture/version, artifact/hash, case result, failure and repair. No VERIFIED until PR promotion completes.

| ID | Required evidence |
|---|---|
| OPS01 | Baseline inventory, backup verification, preserve/removed exact image IDs, disk delta, recent diagnostics and log rotation |
| OPS02 | Saved original policies, legacy pause/new acceptance/new stop/legacy health and policy restoration |
| DB01 | Scoped SQL CAS conflict/concurrent winner, transaction rollback no partial facts, schema mismatch explicit error |
| MQ01 | Actual mandatory return/confirm timeout, same eventId duplicates one handoff, failed transaction no ACK, old generation/fence rejected |
| OBJ01 | Private S3 put/get/head/range, own hash, multipart ETag distinct semantics, restart content/metadata persistence |
| CACHE01 | Redis restart only cache loss, no authoritative facts lost |
| HTTP01 | Health unchanged, readiness200/503 minimal body, no topology/secret leak, API Jackson3/SDK Jackson2 independent round trip |
| SEC01 | Docker runsc runtime actual, socket/network/secrets denied, role privileges distinct, no runtime fallback |
| FS01 | XFS/prjquota UUID mount check, native unprivileged project-quota ENOSPC, actual guest bounded byte/inode denial (ENOSPC boundary recorded), project limit/root free-space evidence, remount/recreate data and limits persist, missing mount denies |
| LIMIT01 | Python/Node/file compatibility, sandbox CPU/memory/PID/timeout exhaustion, platform survives and unknown effects not replayed |
| IDLE01 | One active slot, low memory queues, idle60s teardown preserves data, pending operation not destroyed |
| UI01 |375/768/1440,dark,200%zoom,keyboard,44px and contrast; loading/403/404/offline recovery screenshots |
| SOAK01 |30minutes fixed sequential lightweight tasks with dependency restarts; RSS/cgroup peak/MemAvailable/swap/disk samples, no host OOM |
| REG01 | Existing38host/36Linux/frontend regression and strict OpenSpec, architecture, generation/link checks |
| GIT01 | Both reviewed PRs/CI/SHAs, main-phase sync, actual case evidence, archive, local-only canonical boundary |

Commands: backend/mvnw(.cmd) -B -ntp verify; frontend pnpm contracts:generate/check/test,typecheck,test,build,test:e2e; openspec validate --all --strict --json; new deploy scripts include dry-run default and explicit apply. Cloud tests use pinned artifacts built off-host and loopback SSH tunnel. No production tag until R0.4 and full release gate.
