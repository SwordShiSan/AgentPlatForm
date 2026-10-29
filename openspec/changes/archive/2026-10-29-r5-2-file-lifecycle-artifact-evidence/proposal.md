# R5.2 file lifecycle and artifact evidence

## Why
R5.1 editing reuses immutable versions but users cannot move, delete or restore working files. Public artifact projections must be backed by database facts rather than plausible result JSON.

## What Changes
- Add V19 tombstones, active-path uniqueness, immutable mutation manifests, restore origins and artifact evidence; preserve V1-V18.
- Deliver bounded file/directory move, delete and restore through the existing outbox/MQ/signed-runner/helper authority, not shell.
- Add version/deleted-file/operation/artifact APIs and Vue lifecycle/history/evidence controls.
- Extend the verified isolated launcher for R52; zero real supplier calls, three-minute observation, restore exact R4 after necessary Linux acceptance.

## Impact
All six existing modules retain their boundaries. No production deployment, R5.3, renderer, permanent purge, cloud sandbox or second Agent loop.
