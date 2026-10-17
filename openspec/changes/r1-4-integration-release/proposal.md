## Why
R1.1–R1.3 are locally VERIFIED. Complete cross-cutting diagnostics and integration gates before releasing identity/chat to the existing cloud HTTP entry.
## What Changes
- Correlated request/task/attempt/call failure diagnostics and owner-only UI.
- Remove fixed acceptance limits without resetting facts or removing business budgets.
- Explicit approved HTTP identity deployment, local acceptance, immutable release, serial restore and R0 rollback.
## Capabilities
### New Capabilities
- `r1-integration-release`: integration, trace diagnostics, metering and release gates.
### Modified Capabilities
- `identity-personal-space`: approved explicit cloud HTTP and release gating.
- `queued-text-chat`: remote-quota acceptance instead of fixed local cap.
- `chat-config-snapshot`: cumulative minimal real verification without resetting historical holds.
## Impact
Java six modules, Vue, HTTP contracts, additive V5 if required, local/cloud deployment and canonical documentation. No R2/tool/shell/organization work. Actions remain disabled.

## 2026-10-17 Cold-start resource correction (approved dynamic-budget scope)

Candidate a9d7f81 passed1800.14s observation, real Flash/Pro, consistent backup and independent restore, but final R1 cold-start capture FAIL: SeaweedFS master64MiB was OOM-killed twice (kernel container identity evidence); current State.OOMKilled=false alone hid prior kills, RestartCount=2 correctly blocked. No R1 tag. Restore R0 before repair; retain failed logs/old passing scoped evidence.

Increase only R1 master container and swap ceiling from64 to128MiB; keep GOMEMLIMIT48MiB, official image/source, schemas and app/UI artifacts unchanged. Resident total2336MiB<=2887MiB, hostreserve>=768MiB. This is within user-approved dynamic module allocation, not a third-party source change. R0 configuration stays preserved.

Re-promote config through step→phase→main before deployment. Reuse actual successful model results and cumulative meter (cloud2, estimated437micros; local17/UNKNOWN49152/estimate7434 unchanged); no extra model calls for the resource-only repeat. Observer may accept a protected prior result only after validating its completed persisted task/calls/usage and identity, report reused evidence explicitly, and exercise authenticated stored task/snapshot reads. Run fresh>=1800s observation plus MQ/PG faults and three forced master cold recreations, requiring no automatic restart or OOM. Repeat consistent backup, serial independent restore, R0 rollback and final same R1 startup. Kernel OOM evidence and monotonic restart counts remain blocking; do not reset a failing counter to claim success. Final proof must pass before tag.
