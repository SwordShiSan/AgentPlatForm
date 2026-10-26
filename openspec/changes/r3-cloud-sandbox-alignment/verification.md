> **DEFERRED / 保留方案，暂不实施（2026-10-26）**
> 用户因成本决定继续现行本地Docker＋gVisor/runsc、XFS、受信文件helper和SeaweedFS。本文件下方云方案仅保留研究，**非当前实施项、非R4前置**；不应用、不归档合并到主规格，实施任务保持未完成，云功能验收保持NOT_RUN。云专用2784MiB重分配与V15候选不生效，迁移号不预占；本地完整2880MiB基线继续计入sandbox/runtime/helper。

# Verification — R3.C planning

Status: PLANNING. No application implementation or production mutation in this turn. Canonical baseline main02bad812753987d7b3aa09667d666c66055eba94, released production-r3-95ffaf3b7137 remains unchanged.

| Evidence | Actual result |
|---|---|
| Official product/SDK/limits/storage/lifecycle/network reading and exact source review | PASS as design input, not runtime proof |
| Official Java SDK commit | 98d960b7239a78ccbe580dd766e28db3fc4f325d; five source-file SHA256 recorded in canonical source review |
| Existing cloud list/getInfo | HTTP200 twice; one observed paused base-gh0v instance,1vCPU/1024MiB/10240MiB/envd0.6.1; REST read-only, not Java SDK acceptance |
| Sensitive outputs | APIKey and returned tokens not printed; full response not retained in public repository |
| OpenSpec strict | PASS: selected change valid/no issues; `validate --all --strict --json`21/21 valid (20 existing specs plus this change), no failures |
| Repository and canonical Markdown links | PASS: repository80 links including23 canonical references; canonical811 relative links,0 broken after repairing one historical R3.2 archive link |
| Scoped whitespace/redaction | PASS:48 current planning/body files checked, no actual-key match or whitespace issue; `git diff --check` passed |
| SDK build/Java compatibility/create/exec/files/cancel/kill | NOT_RUN |
| Sync/quotas/remote isolation/lifecycle/regression/new memory profile | NOT_RUN |
| Production deployment/promotion/rollback/new tag | NOT_RUN |

No connect was made because it can resume the paused original instance. No fixture was created, no file altered, no cloud resource terminated and no real model request sent. Protected local inspection/source receipts are under `.cache/cloud-sandbox-research`; current public specs/archived verifications are not rewritten to claim cloud results.

Planning checks used the existing pinned Node runtime and installed OpenSpec, with OPENSPEC_TELEMETRY=0/DO_NOT_TRACK=1 to avoid an unrelated telemetry DNS failure. No tool reinstall or environment restart was needed. No Maven/frontend application test was repeated for these documentation-only changes; all future implementation tests remain NOT_RUN. The canonical body is updated locally outside Git, not automatically pushed or delivered by the repository branch.

## Required functional assertions after implementation
- Normal: persistent prepare -> exact remote binding -> manifest verified -> ready; controlled execution -> quiescent writers -> one collected/versioned result; sleep/delete preserve files, wake restores a new generation.
- Boundary:100MiB/zero-byte, chunk size and total hash,2GiB/100000inode, single transfer/slot, output64KiB/runtime120s, initial2784MiB local profile and actual host reserve.
- Failure: lost create/exec/kill response, DB/MQ/provider outage, stale generation/fence, revoked member, partial sync and output over quota leave truthful state with no replay/duplicate settlement.
- Recovery: original-operation query, exact remote terminal facts, callback exit, immutable file/version hashes, explicit safe rollback and no cross-provider dual writes.
- Compatibility: identity/chat/configuration/diagnostics/SSE stay usable; file uploads/downloads/old versions do not require cloud environment READY; no third-party SDK source patch or local business-execution fallback.

## Current disposition — documentation correction

2026-10-26 later user decision: retain this cloud proposal as DEFERRED, continue the released local sandbox and remove its R4 dependency. The prior PLANNING rows above describe the initial research only. No SDK build, cloud test, implementation, migration or deployment followed. Main specifications and V1-V14 remain unchanged.

Correction checks: PASS — OpenSpec strict21/21; repository81 relative links including24 canonical references; canonical846 relative links;48 planning/body files passed redaction/whitespace/current-DEFERRED checks. Original cloud OpenSpec bodies are retained verbatim beneath the disposition banner, main specs and application/schema/deployment files are unchanged. No business tests or environments were started for this documentation-only update. The retained change being listed by the CLI does not make it an active implementation or an R4 prerequisite.
