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


## Startup driver follow-up and official S3 protocol (2026-10-24)
Native pilot2/3/4 are retained FAILs before file writes; R2 restored each time. Reused-container startup must match current StartedAt logs and the same registry tuple. A fixed sleep and `weed shell s3.bucket.list` topology walk are insufficient S3 readiness probes. A local internal-network reproduction using locked boto3 1.40.45/botocore1.40.76 and AWS Java2.57.3 passed cold readiness, zero/100MiB hash/Range/truncation, filer/S3 restart and complete100MiB readback (Java heap64MiB, nodes128/128/128/256MiB). Private result `.cache/r33-local/transport-1792829469347714900/result.json` retains original commands and hashes; fixture stopped. New `deploy/probes/file_s3_readiness.py` only HEADs the isolated private bucket, reads two explicit protected key files, emits safe categories and has a bounded90s loop; no credentials or topology logs are returned. This is dependency evidence, not the still-pending signed runner/public browser/native quotas/full observation gates.


## Runtime role correction (2026-10-24)
Pilot6 reached actual login and real MQ/XFS storage READY; upload admission returned503. Only fixed diagnostic tokens were retrieved from server-side logs, proving permission denied on space_storage; no raw log, SQL parameters or credentials were exported. r3-file-grants now removes historical table-wide UPDATE and grants only reserved_bytes/committed_bytes/version to API/worker. New actual-role regression verifies reservation, queue claim, stopped no-effect refund, successful v2 receipt settlement and cross-user rejection; quota limit/status changes remain denied. All20 actual PG tests PASS, fixture stopped; V1–V13 migration bytes unchanged, app/helper image production inputs unchanged. Source48b81df app/helper and ca9ecad gateway are reusable for the next native window with the exact revised grants and driver hashes. Whole stage still IMPLEMENTING.

Pending upload-reconciliation gates: completed-object/failed-metadata success, expired-owner fencing without slot release, busy/missing/wrong-hash/close-failure/revocation/CAS failures, same-live-process delayed release and no automatic PUT/delete/complete. Native proof remains mandatory; new code not yet claimed PASS.


## Upload recovery implementation regression (2026-10-24)
Positive-only original-object reconciliation is implemented with typed Mapper/revision CAS, bounded read/hash/close, current authorization and idle-transfer lock at verification commit. Expiry fences metadata only and does not release a held slot or reservation. A same-live-process timer retries only an already confirmed pending release. Unit/application targeted tests19 PASS; the affected infrastructure/S3 tests also passed. Actual PG22 tests PASS, including restricted API/worker recovery, held-slot refusal, stale owner/revision and unchanged reservation/no automatic outbox. This is not native fault evidence yet. SDK write failures now produce stable FILE_OBJECT_WRITE_UNKNOWN (503, retryable=false through the existing file Problem handler); internal logging is restricted to fixed stage/category and SDK class/method identities, never exception messages, object keys, headers or bodies. The sentinel logging assertion passed. Native pilot7 proves one zero-byte public API/MQ/signed runner/XFS helper commit;100MiB API staging returned500 under the older image, retained pending with quota. R2 restored; no OOM. Larger pipeline/fault/browser/observation gates remain pending.
