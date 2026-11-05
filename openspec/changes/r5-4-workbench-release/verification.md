# Verification

Status: LOCAL_FUNCTIONAL_PASS / PRODUCTION_PENDING (2026-11-05). Source-bound index: `evidence/r54/acceptance-index.json`. The isolated R54 window passed and restored exact R4; the R5 production configuration is STAGED_NOT_RUNNING. No R5 production migration, release or real supplier acceptance has occurred yet.

## Current bounded evidence

- Strict validation passed before implementation and passed again on 2026-11-05.
- Full reactor/profile run `engineering-1793879228741231200` passed 319 tests/package/renderer checks; `engineering-1793881912400504800` passed 58 affected bundle/SDK/package tests. Two fresh large Plan/exact-approval tests and eight real PG tests cover the later test additions. Counts overlap and are not summed as unique tests. Source continuity validates the unchanged production inputs.
- Actual isolated PostgreSQL runs under `.cache/r54-local/postgres/` verify V20→V21/runtime baseline1, attachment ownership/idempotency/quota, shared extraction slot/receipt, actual API/worker/runner grants, first-task rollback/concurrent request identity, dynamic token reservation and retained observed usage. Failed test-driver runs remain preserved with their original status.
- Frontend types/unit/build/contracts passed with hash-identical production inputs. Real browser `browser-1793885261000584100` passed source preview, attachment refresh, responsive layout, themes, storage and account isolation; the separate 10k-node fixture passed pagination/virtualization without creating physical files.
- Real PG/MQ/S3/runsc DOCX/XLSX/PPTX/PDF/text extraction, original-byte/hash reads and immutable file-source input after replacement passed. The real SDK received a 171873-token estimated input under frozen256K/4096 configuration; deterministic supplier stand-in only. Over1MiB actual SDK restore, Plan/exact-tool approval across attempts and PG state/checkpoint qualification passed separately.
- The final native window observed182.214seconds/32 serial read rounds, min sampled available memory1316MiB, full configured budget2880MiB and no pending asset/heavy/transfer facts. Exact original R4 container/image/policy/UUID restoration passed. Samples are not absolute peaks or a minimum physical-host capacity claim.
- Historical failures are retained: stale Desktop sockets, parser packaging, unregistered bundle, proxy-routed browser502, API384/runner256/MQ192 cold-start OOM and test-driver scope/JSONB/port mistakes. Corrective receipts supersede only their affected scope; unresolved/NOT_RUN items are not silently upgraded.
- Read-only cloud check found the original R4 graph (11 services) running, production XFS UUID unchanged and official runsc hash correct. This is a preflight, not an R5 acceptance result.

| Gate | Assertions | Status |
|---|---|---|
| Specification | Approved plan, ADR/contracts, strict | PASS |
| Context | 256K/512K SDK admission, >1MiB SDK/PG state, Plan/exact approval, dynamic usage/legacy | PASS; real Flash pending cloud gate |
| Attachment | Standalone upload, fixed version, all five text formats, pinned input/hash, source ACL | PASS |
| Reliability | Affected real PG/MQ/S3/roles/duplicates/UNKNOWN/receipt facts plus matching R2–R5.3 unchanged gates | PASS within indexed scope; no new whole crash-matrix claim |
| UI | Actual R54 API/browser plus unchanged editor/trash/artifact contracts; 10k virtual fixture | PASS within indexed scope |
| Resource | Full 2880MiB budget, host reserve, serialized heavy jobs, 182s observation and exact R4 restoration | PASS; production observation separate |
| Promotion/release | Two protected PRs, exact candidate, targeted restore and R4 matched snapshot rollback, R5 final smoke and production tag | NOT_RUN |
| Closure | Archive, ledgers, Skill and two protected local delivery copies | NOT_RUN |

## Versioned R5 release boundary

`r5-release-profile.json` fixes schema21/baseline1, eleven resident services, no permanentOffice and the2880MiB full budget. R5 application512/320/320MiB, gateway32MiB; environment caps stay as accepted. The test-only64MiB provider uses the test S3 allowance, while production restores S3's original224MiB.

The gateway binds loopback during maintenance. `r5-release-control.py` stores matched pre-R5/R5 snapshots; R4 rollback restores the pre-upgrade snapshot rather than attaching R4 to tombstone/attachment data. `r5-open-writes.py` persists an irreversible-for-this-window opening intent before public exposure; all old-snapshot restore paths refuse afterward. Isolated restore, real Flash, production observation, rollback and opening remain NOT_RUN until their own host receipts pass.

First production attempt: FAIL before runner readiness (incorrect release-folder preview staging root); supplier calls0, public writes stayed closed. Automatic restoration then encountered the unassigned original loop after unmount. The narrow retained-recovery command verified the restored4GiB image SHA256/UUID, reattached recorded `/dev/loop0`, compared the schema18 database fingerprints and restored exact R4 containers/images/policies. Recovery PASS is not R5 release PASS. Corrective configuration/loop handling uses the same immutable application candidate and a fresh protected release-tool PR; original logs/snapshots remain retained.
