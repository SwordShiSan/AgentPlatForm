# Verification Record

## Closeout update — 2026-11-09

**Current state: acceptance complete with explicit PASS_LIMITED boundaries; protected promotion, archive, and final package remain pending.** The R6.3 code and local/isolated-cloud acceptance candidate are ready for step→phase→main promotion. Until those protected promotions and archive/package are complete, this change is not VERIFIED; R6 remains 2/4 VERIFIED and not RELEASED. Cloud production is restored to the original R5 containers. R6.3 has not been deployed or tagged. Real supplier calls: 0.

The signed cloud application path is now proven end-to-end: attachment upload → signed TEXT_EXTRACT on cloud runsc → private S3 hash readback → knowledge index READY → semantic marker hit. Result SHA256 `21de780cd1fda9ed36c41009706b9f95084147882fc1210d622a9e3c075053ab`; journal SHA256 `abd71f8a4248243cbcacb03b7026ed767df88371ed1158c7efb51c4c7e7fea8f`. The physical sandbox exists only on cloud Linux. The local workstation has no runsc, did not install it, and did not start a local sandbox.

Additional bounded evidence: injected extraction-identity mismatch preserved the prior active index (`9d4fceb0fc413d2cf64dbcfe12d4a38229afb5aa91c2a3dbb8ccac0cdbe700e3`); authenticated source deletion removed search hits and a subsequent deterministic task had no knowledge references (`77b78be68aaf6f668b1f15389c5c69ff1ca658a672016f5c8427c47d0d843ad3`); FACT/PREFERENCE/PROCEDURE expiry and audit behavior were exercised (`2d857c5379087f8d99a9b3ec3e7a2f7b4b9957945a6343a1f8588e80bb8076be`, procedure addendum `d4f648221afb3e5f7afdc0bd8cb4fa0f66ca4814710068ce86e67c0739c94ffc`). A durable projection survived API restart with canonical history hash unchanged (`be198c09153c92436c09359cf7fefab73571b677b7dd0352a4b18eb911be4066`). Java process VmRSS/VmHWM was measured separately from cgroup memory (`94d6b77eef730e3e8a2e964e4eb2addfbb7445677692fec8a57bb5a88531c10f`); shared-slot admission rejected inference while a synthetic sandbox reservation held the database slot (`a265577701a20ece7dbbe02691e58d1eb83fbf03214ba30dd948ed5bc30c0fb6`). Final cloud R5 restoration receipt SHA256 `8fa1e2b850d202994f9ecb9def98ee2bdfadc2e5cdb054465111712dec79507a` confirms original R5 API/gateway/runner/worker identities remain running and the R6.3 candidate runner is stopped.

Boundaries remain explicit: no separate knowledge-route ACK-loss/redelivery injection; the failed-build case uses a controlled pre-extracted fixture; source revocation and memory expiry are bounded to recorded API/test fixtures rather than every live ACL race or long wall-clock soak; projection cancel/in-flight DB failure was not injected; the shared-slot receipt is admission logic, not simultaneous full-stack plus physical sandbox stress; browser testing used CSS viewport widths and a keyboard subset, not true 200% zoom, full keyboard, or a visual contrast review. These remain PASS_LIMITED/NOT_RUN as applicable and are not described as completed test cases. Retrieval quality/scale uses the bounded corpus stated in the evidence index. Compression remains a deterministic derived projection, not semantic summarization and makes no extra model call.

The machine-readable evidence index is `evidence/r63/acceptance-index.json` (current candidate status is pending protected promotion). Existing detailed records below are the 2026-11-08 initial snapshot and are retained for history; this closeout update supersedes their old current-status and remaining-gates statements.

---

## Initial acceptance snapshot — 2026-11-08 (historical)



**Historical snapshot only:** R6.3 was IMPLEMENTING at this point in time; the closeout update above supersedes this status. Product code and targeted acceptance are substantially complete, but the durable signed TEXT_EXTRACT-to-index path, full old-index/replay failure matrix, durable context-projection recovery matrix, model cold-start/shared-slot evidence, and protected PR/archive/package gates remain incomplete. R6 remains 2/4 VERIFIED and not RELEASED. Cloud production remains R5; no R6.3 deployment/tag or real supplier request was made.

The local workstation intentionally has no `runsc` sandbox. Physical gVisor renderer smoke was performed on the cloud Linux host in a separate, isolated run; it did not modify the R5 production containers and does not prove the signed application extraction pipeline. Do not report the sandbox as locally installed or locally exercised.

## Candidate and environment identity

- Branch: `step/r6.3-knowledge-memory`; base Git HEAD: `abc8dd11f11cfd7fd7195cb9a2012d69975b1d77`; working tree contains the uncommitted R6.3 candidate. Results bind to the candidate input manifest and image IDs below, not to a clean release SHA.
- Local acceptance project: `agentplatform-r63-local`; dedicated DB `r63_2fa963f96f7f`; required local stack healthy after the V23 upgrade test. PostgreSQL has no host-published port; the upgrade probe ran from a temporary Maven container on the private Compose network. `python deploy/scripts/r63-local.py stop` is still required at final cleanup; volumes are retained.
- Product image input identity: `d99dd37ea8e25df1f5706dfe2dc9aff131b7b0720559216f49b9061b1139564b`; source revision at build `abc8dd11f11cfd7fd7195cb9a2012d69975b1d77`. Later changes were limited to the explicit fixture-only allowlist in ignored `.cache/r63-local/runtime-config.json`; no product image source drift was accepted.
- API image config: `sha256:9d8ee1416a202c37769d2a138d1ace83dcfa161ffca4338612da76b09c1f83ba`.
- Gateway image config: `sha256:b4e441c437ffb26059060fec3964b720ff568e7b2b07e1f291b6d29a3e9f769e`.
- Deterministic provider image config: `sha256:211974dc2ba37540e78299effa24a9425f9700593648be1310f8583aa98fcfd4`.
- Reused file helper config: `sha256:a0f2fb315bbd72fac1eb5c38e2216a3cd285c778a2de301bd117572016e729ec`.
- Storage probe image config: `sha256:b96f810c0b18749f7f4caa777718d4c14142776540726ba73cf37917822a79cb`.
- E5 model repository commit `761b726dd34fb83930e26aab4e9ac3899aa1fa78`; ONNX SHA256 `f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193`; tokenizer SHA256 `0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39`.
- Raw logs, fixture credentials, database dumps and browser profiles stay in ignored `.cache/r63-local`; no secret or raw DB backup belongs in Git.

## Required evidence

| Gate | Evidence and scope | Status |
|---|---|---|
| OpenSpec strict | `OPENSPEC_TELEMETRY=0 DO_NOT_TRACK=1 openspec validate r6-3-knowledge-memory --strict` exited 0. With telemetry enabled the CLI also exited 0 but printed a non-fatal DNS error for its analytics endpoint; telemetry-disabled validation produced the clean final result. | PASS |
| Maven full verification | `backend/mvnw.cmd -B verify`; 7 modules, 387 tests, 0 failures/errors, 4 skipped; Java 21.0.10. Log SHA256 `5ca11b42a6f62c998c01d55932319b0a243a8262490c462557cae4121826c900`. | PASS |
| V23→V24 migration | `KnowledgeV23UpgradePostgresTest`, executed inside `maven:3.9.9-eclipse-temurin-21` using the mounted project Maven 3.9.11 binary and the private Compose network. Separate empty `r63_upgrade_r63_2fa963f96f7f` DB migrated to V23, received existing identity/space rows, then migrated/validated V24; 1 test, 0 failures/errors/skips. Log SHA256 `59d767fad71cfe49a3bf9e3d5ae45cd84e6dcb393dda50b900c50fb11e922716`. PostgreSQL remained unexposed to the host; the fixture stack was reconciled to remove the temporary port binding. | PASS |
| Persistence boundaries | `python deploy/scripts/check-persistence-boundaries.py`; 514 production Java files scanned, 3 exact diagnostic/TypeHandler exceptions; exit 0. | PASS |
| Frontend | Node 22.23.3; typecheck/build/contracts and illegal fixture checks passed; 18 Vitest files, 81 tests; 87 local links and 27 canonical external references. Generated client had no drift at the recorded check. Frontend log SHA256 is in the evidence index. | PASS |
| Fresh V24 / real PG-MQ-S3 smoke | Dedicated R63 local DB `r63_2fa963f96f7f`, real PostgreSQL/RabbitMQ/SeaweedFS: attachment object hash roundtrip, index `READY`, one relevant hit, unrelated query zero hits, PENDING→ACTIVE→DELETED memory, protected deterministic task received personal-memory and knowledge evidence. Supplier calls 0. Knowledge input used a controlled pre-extracted text object (`FIXTURE_ONLY`), not the physical renderer. Result SHA256 `72f1bd4f98362fa5e42de6785f3bf92f00049852bd59f01180469455d02e0758`. | PASS_LIMITED |
| V23→V24 upgrade | Existing identity and personal space survived additive V24 migration; runtime baseline remained schema 1. | PASS |
| Durable signed TEXT_EXTRACT→index | Cloud physical renderer smoke and local pre-extracted-text indexing were separate executions. No signed HTTP runner + source extraction + S3 output + index build was completed in one operation chain. | NOT_RUN |
| Knowledge build/replay faults | Successful outbox/confirm/claim/ACK/lease/fence/ordinal path reached `READY`. ACK loss, redelivery/continuation, database/broker interruption, failed build preserving previous active index, and retry boundaries were not exercised as a complete matrix. | PARTIAL |
| Retrieval quality and scale | Fixed 10 Chinese/English/cross-language queries: 10/10 Hit@8; fixed no-answer probes: 0 hits. 10k chunks/40 samples p95 110.75 ms; 20k/40 p95 200.53 ms. The scale corpus was one real indexed bilingual source plus synthetic unrelated zero-vector rows, so this is a bounded ranking/performance probe, not broad retrieval-quality proof. Result SHA256 `a0fcf7cbcc7caf66e27c4a65275e813c783783ce7b06158afdc37837585467af`. | PASS_LIMITED |
| Personal memory | Real API matrix: owner workspace accepted; foreign workspace denied; administrator/restricted user isolation; concurrent same-topic confirms yielded one ACTIVE row; DB-time FACT expired and PREFERENCE required review, with audit rows. After deleting all active admin fixture memories, a subsequent deterministic task returned `memory=0` (receipt SHA256 `5309a7be6848d5fef9550768314585544b957f275043b44a0a40d269b1ef3e0f`). A code review found workspace-scoped memories were not passed into task context; `HarnessData.Scope` now carries the frozen task workspace binding, and the adapter uses that same scope for recall and source revalidation. Two focused unit tests pass for personal+bound-workspace recall and legacy-unbound personal-only recall. Full source-revocation/no-recall and remaining category/expiry boundaries remain NOT_RUN. The first no-recall assertion incorrectly required an unrelated knowledge hit; the completed task was re-read and evaluated against the intended memory-only assertion. Matrix SHA256 `2d857c5379087f8d99a9b3ec3e7a2f7b4b9957945a6343a1f8588e80bb8076be`. | PASS_LIMITED |
| Context projection/compaction | Unit tests verify only eligible assistant prose is projected, user messages and whole tool pairs remain, canonical input list is unchanged, and required over-limit input is rejected. Real deterministic protected task received memory and knowledge references. DB projection CAS/restart restore, cancellation/revocation/failure windows, and explicit before/after canonical ledger equality were not run. No separate compaction model request was made. | PARTIAL |
| Physical renderer/gVisor | Cloud Linux, official runsc, fixed renderer image config `sha256:502ba213ee70a1ed10e6a01aec519fb64c757a0c8201c774553c49d58f646545`, runsc SHA256 `210b437a9cfae51e8f8c9074ed19b8b5e59477178e2e391a18117d8d6b924f7a`; DOCX/XLSX/PPTX extracted, exact output hashes recorded, no OOM, containers stopped (exit 143 after stop), R5 container identities unchanged. Workstation has no sandbox and did not run one. This is a renderer component smoke only, not the durable signed application pipeline. Receipt SHA256 `0d58203eef4967c8b16f3853ce5d9987919b87ca22ebaf7eb7c00a94724cccf5`. | PASS_LIMITED |
| Browser | Microsoft Edge headless, real local API: admin login; knowledge base read and search hit; memory page and deleted-state filter; restricted-account isolation; no page/console errors; search button focus; dark theme selection retained; knowledge and memory pages had no horizontal overflow at 375/768/1440. 200% zoom, full keyboard operation and dark-theme visual contrast review were not run. Receipt SHA256 `52327303b172460723eb912f6f6f4a19e41fd0cb39a7dfe3029d66283e4f8aa0`. | PASS_LIMITED |
| 180-second resources | 184.9 seconds, 22 samples, 0 budget/health violations; config+runtime/helper reserve 2880MiB, no swap cap expansion; local sandbox not started (intentional). Min host MemAvailable 4688.7MiB, min local root free 152,716,062,720 bytes, sampled swap max 19.4MiB; API/worker maxima are container sampled values, not cold RSS. Result SHA256 `cc341b4396000e145d40d14e98bebac0def4d8719bb43188620faff8dbb7cdb8`; JSONL SHA256 `57ef95631b2b737848feb45bfb57aba0aa7f7e64812efa118eaca36a4936f6e1`. | PASS_LIMITED |
| Model cold RSS / shared heavy slot | No independent cold-start RSS receipt and no combined real renderer/sandbox plus complete application stack on the same heavy slot. Physical runsc smoke was on cloud; local sandbox absence is expected. | NOT_RUN |
| Supplier requests | Deterministic provider only; acceptance result counters remain 0. | PASS |
| Production and release | No cloud R6.3 deployment/migration, production tag, PR promotion, OpenSpec archive or final delivery package. Cloud R5 remains unchanged. | NOT_RUN by design / pending phase gates |

## Non-negotiable invariants retained

- A failed/incomplete index build cannot replace the last active index; implementation exists, but the old-index failure scenario has not been fault-injected in acceptance.
- Authorization/source version are checked before ranking and before model admission.
- Memory is not ACTIVE before explicit user confirmation; deletion immediately removes the candidate from future lookup paths by query predicate. A real deterministic task after deletion observed zero memory references. Source-permission revocation after activation remains a distinct unverified case.
- Context projection is derived and must not mutate canonical chat facts; DB recovery/competing CAS proof remains pending.
- UNKNOWN model calls are not replayed; supplier call count remains zero.
- No R6.3 production changes occurred. Cloud R5 and its container identities remain unchanged.

## Remaining gates

1. Durable signed TEXT_EXTRACT-to-index application pipeline on a supported cloud Linux runner or an explicitly authorized isolated runner profile.
2. Knowledge failure/replay/old-active retention and complete source-revocation matrix; memory source-revocation and remaining expiry boundaries.
3. Context projection durable CAS/restart/cancel/revocation/failure/canonical-ledger matrix.
4. Model cold-start RSS and heavy-slot combination evidence consistent with the cloud-only sandbox design.
5. Full browser keyboard/200%-zoom review if retained as a hard requirement, then final checks and evidence freeze.
6. Only after all mandatory gates pass: protected step→phase→main promotion, OpenSpec archive, final clean-main evidence/body package and hash-checked protected copies.

Until these gates are completed, keep the change open and **do not mark R6.3 VERIFIED or R6 RELEASED**. Any unresolved/unrun item remains explicit; no R6.4 work starts.
