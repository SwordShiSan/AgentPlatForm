# R3.3 verification
Status IMPLEMENTING; every implementation gate initially NOT_RUN. No vendor calls or production mutation.
## Commands and evidence
- backend/mvnw.cmd verify: DDD/Enforcer/Mapper loading and old fixtures; deploy/scripts/check-persistence-boundaries.py.
- frontend pnpm frozen install, typecheck/test/build/contracts check+negative fixtures, links; OpenSpec strict.
- Real isolated PG freshV13 and V12 upgrade; API/worker/runner limited roles, rollback, upload idempotency and concurrent reservation/replacement.
- Real MQ confirm/ACK failures/duplicates/worker stops and DB/broker outages; original operation facts preserved.
- Seaweed S3 zero/100MiB/hash/Range/multipart/restart/DB-after-object failure; no public bucket or browser credentials.
- File helper zero/truncated/oversized streams, rename-before/after crash, old fence/revocation, NFC/emoji/links/nonregular/foreign space, quota bytes/inode/temp overhead, missing mount and consistent remount.
- Browser real API create/upload/explicit replace/412/refresh/response loss/download/strict preview, two users+admin,375/768/1440/200%/keyboard/themes.
- Bounded profile >=1800s repeated file workload with dependency interruption: memory/swap/threads/connections/disk/staging metrics; final exact R2 restore.
Record each command/source/image/config/hash/exit/assertion separately. Preserve FAIL/NOT_RUN. Mock tests never replace native gates. Final immutable payload local/cloud two-copy receipts required before archive completion.

## 2026-10-24 preparation evidence (IMPLEMENTING)

Strict-before-code PASS. AWS2.57.3 Enforcer/compile PASS; private progress index `.cache/r33-local/progress.json` binds command hashes. Trusted64MiB helper100MiB native write/hash and Linux path tests PASS, stopped. Final independent four-node S3 transport PASS for zero/100MiB/full SHA256/Range/truncation/no multipart leaks with Java64MiB heap; all nodes running/no OOM before deliberate stop. Real PostgreSQL V12→V13 and four scoped transaction/competition/unknown tests PASS; fixture stopped. New file HTTP/filter/DTO and bounded stream tests PASS. No cloud changes or vendor calls. Full runner/MQ/browser/XFS/fault/observation/promotion remain NOT_RUN; no VERIFIED claim. Original packaging/preflight/cold-start/OOM/stream truncation failures are preserved, not overwritten.


## 2026-10-24 lifecycle implementation batch (not final acceptance)
Signed file runner, immutable physical intent/stop receipt, Spring AMQP confirms/MANUAL ACK,60/10 ownership renewal, scoped directory-page SCAN jobs, cross-process single transfer, query-only stopped-result reconciliation and conservative staging expiry are implemented. Full six-module Maven verify PASS through the fixed host launcher; sixteen actual isolated PostgreSQL transaction/role tests PASS; frontend41 tests/type/build/contracts/links PASS; latest Linux helper unit suite PASS. These do not replace the actual docker-java runner/MQ/browser/XFS gates. Protected private index `.cache/r33-local/lifecycle-progress.json` binds report hashes. Earlier restricted Windows atomic-move and outdated-root-test failures remain preserved; not marked PASS. Cloud read-only check confirms existing R2 ten services; root free5645221888bytes, insufficient for a new4GiB image plus5GiB spare. No production mutation or real provider calls. Overall R3.3 remains IMPLEMENTING.

Framing: official docker-java3.7.1/HttpClient5 loopback101 must pass zero/100MiB without EOF; helper tests must reject missing/bad trailer before rename. Actual Engine and XFS remain separate mandatory evidence.

2026-10-24 framing batch: official SDK core/HC5 zero+100MiB loopback101 PASS (host, 6.526s test);15 Linux helper tests PASS. Raw restricted-loopback permission failure preserved separately. Latest protocol consumption/projection refinements await affected regression before candidate build.


## Native pilot1 (2026-10-24): retained FAIL
Source48b81df artifacts built/exported/imported with config/hash verification. Independent V13 install, runtime1, private PG/MQ/S3 and runner started, but the new file gateway failed because fastcgi_temp_path defaulted to read-only /var/cache/nginx. File operations were NOT_RUN. Exact original R2 ten-service restoration and production UUID preservation passed; test resources stopped. Do not replace this failure with later passing evidence.
Correction reuses established nginx profile temp paths, adds a reusable read-only/no-network32MiB gateway start smoke and verifies the actual VITE_WORKSPACE_FILES_ENABLED flag in the output bundle. Earlier candidate build incorrectly set VITE_FILES_ENABLED; frontend source was unaffected, but that artifact must not be claimed as the file UI. Backend/helper48b81df trees can be reused when unchanged; every gateway artifact records its own exact source and build flags. Native file chain and all remaining mandatory gates are still pending.
