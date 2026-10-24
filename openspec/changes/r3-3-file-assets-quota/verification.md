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
