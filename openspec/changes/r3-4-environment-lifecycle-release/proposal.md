## Why
R3.3 has verified public immutable files, but environment controls still expose preparation only. Users need durable sleep/wake/delete and truthful cleanup; R3 needs a combined memory budget, integrated recovery and a protected production release.

## What Changes
- Add environment-only sleep/wake/delete and safe intent-preserving retry; preserve workspace directories, project quotas, file versions and object accounting.
- Add V14 on the existing operation/receipt/outbox/inbox model and ENVIRONMENT_V3 signed commands. Preserve V2 historical queries and migrations V1-V13/runtime baseline1.
- First validate the approved whole-stack resource profile, then implement lifecycle and UI using existing framework adapters.
- Verify locally and in isolated Linux windows, promote step->phase->main and release the same immutable R3 candidate after integrated observation, restore and R2 rollback drills.

- Add explicit protected maintenance scan-admission control so consistent freeze drains original work instead of assuming all background producers stopped. Normal scans default enabled; no public maintenance endpoint.

## Capabilities
### New Capabilities
- `environment-lifecycle-release`: durable public lifecycle, precise stop/cleanup facts, retained files and R3 release evidence.

## Impact
Public additions are sleep/wake POST and environment DELETE, all versioned/idempotent. Changes cover six-module DDD application/domain/infrastructure/trigger/API, MyBatis XML, Vue and deployment scripts. No public shell/tools, workspace/file deletion or real provider calls. GitHub Actions stays disabled; protected PRs remain required.
