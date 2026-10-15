# R0.4 accepted local/cloud implementation design

## Current decision
User-approved replacement2026-10-15. Earlier source-rebuild/blanket scan-blocking design is superseded; Git history retains it. Same step/change, stable base a1c09fea. No database migration or business features.

## Reproducible environment
Use official middleware manifests from stable base for PG17/vector0.8.1, Redis7.4, RabbitMQ4.3.6 and SeaweedFS4.48. Use currently locked official non-root Nginx directly. Build project API/framework/storage/sandbox images locally with current patched app dependencies; preserve SDK and contracts. Official runtime20261005.0 full package uses systrap, not Docker default. Third-party source candidate Dockerfiles/patches are historical and not in acceptance execution.

## Evidence and export
Local build records exact source SHA, image architecture/config IDs, registry references, exported manifest and archive SHA. Cloud loads the same artifacts and verifies config identity. Scans are informational; preserve raw failures/NOT_RUN and dependency/license/identity records. Never claim vendor findings fixed. Functional and agreed isolation/resource/backup/restore gates remain mandatory.

## Workflow
Disable repository Actions and cancel queued/running builds. Archive active workflow, retain PR/non-force protection, remove retired CI required contexts and use exact-source local/cloud review evidence. No manufactured check results or direct protected-branch push. Local independent roots/ports/credentials; cloud builds/scans prohibited. Review existing working changes; pause unused package ownership and third-party source work.

## Cloud acceptance and failure
Recovery uses explicit non-business fixtures: a dedicated pf.r04_restore_vector table and a tiny trusted workspace fixture with special filenames/empty directories. Capture the vector values, schema version and state-slot aggregate hash before backup; require identical restored values. Workspace bytes/inode limits of project100001 are restored after the temporary sandbox probe clears its test quota, and a real allocation beyond the restored limit must fail while global space remains. This adds acceptance data only, not a Flyway migration or public business API.
4CPU/3655MiB/40GiB, root reserve5GiB, one execution slot/sandbox, original runtime limits and4GiB XFS/prjquota/UUID guard. Old/new/restore stacks serial. Validate SDK/storage and UI locally, then cloud sandbox/quota, fixed candidate >=30min, coherent cold backup and independent restore. Preserve original XFS UUID; only restore copy changes UUID. Failure stops only new resources and restores exact legacy containers/policies/entry. Prove component infeasibility before discussing stack replacement; continue independent work meanwhile. R0.4 VERIFIED only after required gates and two PRs; R0 unreleased.
