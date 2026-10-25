# R3.4 verification

Status: PREPARING. All implementation/runtime/release gates NOT_RUN. Supplier calls must remain0. Prior R3.3 PASS records are prerequisites only.

## Resource window1: retained FAIL, original R2 restored
Independent root r34-full-20261025t030104z,4GiB XFS UUID82aff679-8af2-4199-b9f7-ec1a912997a6 and fresh credentials. PG256/MQ256/Redis64 and Seaweed128/128/128/256 started; runner224 exited1 without OOMKilled or JVM OOM marker, before API/file/canary gates. Server-side fixed-message matching proved `Pinned sandbox images and trusted workspace root required`: previous SandboxPolicy permits only anchored r32/r33 acceptance roots. Result SHA2564b24ca8448d59fddd04325096c201996318406f37683a0f859aeff40803d5df4. Original R2 ten services/restart strategies/schema9/productionUUID restored; all fixture resources stopped/unmounted. Capacity acceptance remains NOT_RUN; do not tune memory based on this non-capacity error or mark it PASS.

Historical R3.3 test XFS was archived with two cloud and two protected local compressed/uncompressed SHA-verified copies before exact original withdrawal; production/volumes/images untouched. Immediate post-unlink disk accounting assertion failed, original withdrawal receipt preserved; later read-only reconciliation confirmed source absent and actual4GiB free without another deletion. Three historical exports were likewise withdrawn only after two local byte-verified copies and retained Docker config identities. The new fixture was staged without production pause. An escaped-newline error in a literal remote payload prevented initial XFS allocation; all literal payloads now compile before resume. The static payload checker has three pure logic tests PASS; an initial Windows AppContainer Temp-denied test run is preserved, tests now exercise the parser directly without unnecessary filesystem temporaries. These are preparation/driver evidence, not lifecycle acceptance.

Preparatory root compatibility: allocator accepts the anchored r34 namespace; SandboxPolicy accepts r34 while retaining r31 sandbox rejection, mutable image/device and traversal/lookalike guards. Nine actual policy tests PASS, log SHA25676097f3c989feaa96322dfca8b20b0f7ed734d1cce49e878074b6878565898f9. The old quota test formerly used r34 as its unapproved future case; it now explicitly accepts approved r34 and rejects unapproved r35. The first run with that stale assertion remains FAIL. Three pure payload-parser tests PASS, SHA256383a12fba9b5b327054a24ce7e5739569c4f0b6621facd981f2d6a4271063b3c. These do not prove the resource or lifecycle gates; rebuild/attest the affected platform artifact before the original-budget resource rerun.

## Evidence matrix
| Gate | Normal | Boundary/failure | Recovery | Current |
|---|---|---|---|---|
| Framework/contract | Maven/DDD/Enforcer/Mapper/XML/Vue/generated client | invalid fixture/persistence boundary | old codec/program/migration compatibility | NOT_RUN |
| Resources | full cold start/login/100MiB/canary | OOM/limits/floors/all temporary processes | cleanup and same profile restart | NOT_RUN |
| Admission/queue | versioned intent/outbox/claim/ACK | stale/changed key/double owner/confirm/ACK loss | original identity and one effect | NOT_RUN |
| Ownership | authorized generation and exact identity | revocation/stale fence/signature/body corruption | proven stop only; UNKNOWN not replayed | NOT_RUN |
| Lifecycle | sleep/wake/delete/reprepare and idle60s | active file lease/crash before-after stop/removal | receipt-first cleanup/query/host proof | NOT_RUN |
| Files | current and old version hash/quota retained | missing mount/byte-inode/path/cross-space | remount/new generation readback | NOT_RUN |
| Browser | actual login/lifecycle/retained files | lost response/stale version/offline/foreign admin | refresh original operation/explicit retry | NOT_RUN |
| Long observation | continuous>=1800s fixed candidate | controlled dependency faults | no unbounded resources; R2 restored after test | NOT_RUN |
| Release | same verified artifacts and HTTP/Host | capacity/backup/restore failure | isolated serial restore/R2 rollback/same R3 restart | NOT_RUN |

Bind source, component build inputs, platform/config/manifest/archive identity, migration hashes, environment/commands/exit/assertions and SHA256. Capture first failure before finally cleanup. Screenshots/logs redact secrets; no raw SQL/keys/config environment or password-bearing Playwright logs in public evidence. Browser production auth uses the already explicitly approved SSH loopback forward with browser-only Host mapping and page fetch; no direct public HTTP credential fallback. Continuous intervals are never combined. Resource samples are not absolute peaks. Archive/VERIFIED/RELEASED require their actual gates, not these checklist entries.
