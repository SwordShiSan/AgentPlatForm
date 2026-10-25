# R3.4 verification

Status: PREPARING. All implementation/runtime/release gates NOT_RUN. Supplier calls must remain0. Prior R3.3 PASS records are prerequisites only.

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
