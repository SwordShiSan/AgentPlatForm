# Verification

Status: PLANNED. All implementation/runtime checks NOT_RUN. Specification strict must pass before coding. Zero real supplier requests. Final status cannot be VERIFIED until mandatory cases and protected promotion complete.

| Gate | Assertions | Current |
|---|---|---|
| Normative | approved Plan, five artifacts, canonical plan/ADR/contracts and strict | NOT_RUN |
| Physical feasibility | actualDOCX/XLSX/PPTX, CJK, PDF/image, bounded source/output,512MiB process, no live Office leftovers | NOT_RUN |
| Engineering | Maven verify/DDD/Enforcer/Mapper, persistence boundaries, frontend type/unit/build/localPDFworker, contract regenerate/illegal fixtures, links/strict/diff | NOT_RUN |
| Migration | cleanV20, V19→V20, untouchedV1-V19/runtime1, valid independent preview/asset/role relations | NOT_RUN |
| Durable success | realPG/MQ/S3/signedrunner, jobcache/versionhash/settlement once, original download unchanged | NOT_RUN |
| Fault and recovery | duplicateconfirm/ACK, response-loss, owner/fence, stopped infraretry≤1, output-beforeDBfailure, uncertainstopBLOCKED, originalcommandquery | NOT_RUN |
| Content | threeOffice formats/CJK, nativePDF pages/Range, safeimages/GIFfirstframe/WebP, UTF8/zero, staticHTML, corrupt/encrypted/mismatch/limits | NOT_RUN |
| ACL | two users/admin isolation, revoke, source move/tombstone pinned version access, same-spacecache doesnotauthorize | NOT_RUN |
| Resource | durable renderer/sandbox mutual exclusion,≤2887MiB caps, reserve768MiB/5GiB, normalchat/download available, no swap/runcfallback | NOT_RUN |
| Browser | current/history/artifact, refresh/loading/failure/BLOCKED/download, sequentialRange, logout cleanup,375/768/1440/200percent/keyboard/themes | NOT_RUN |
| Observation | final candidate180seconds; extend to300only actual growth concern; sampler values not absolute capacity | NOT_RUN |
| Closure | exactR4restore if window, stopacceptance, two protected body/evidence copies hashes≥30days, protectedPRs/archive/Skill/ledgers | NOT_RUN |

Historical unchanged R4 XFS/gVisor/recovery evidence may be reused only for identical inputs and stated scope; new renderer isolation/stop/admission is actually tested. Mock protocol tests do not prove physical Office/runsc. Failures retain original evidence and first error; no re-running known UNKNOWN mutations.
