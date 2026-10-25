# R3 release operations

The accepted backend runtime is b8dd176a763b; the unchanged frontend is fd2aa8a30ad6. Final API/worker/runner config ID is sha256:556f88c54119aaf55db4fddda48d4fddf09bf502bbfc549e44cd5fa954874fc7. Deployment grants/configuration and test evidence are recorded separately. Keep R2 containers and their original settings for rollback. Existing production PostgreSQL, RabbitMQ and object data are reused serially; never import acceptance accounts, passwords or files.

Production filer uses PostgreSQL metadata. Do not copy the acceptance LevelDB filer configuration. Preserve existing model encryption/cursor keys. Create independent production runner/control signatures and narrow runner/file credentials. Runner alone receives the Docker socket; no public runner port.

Resource allocation can change within2887MiB including sandbox512, runtime128 and helper64. Actual values and exact container IDs must be receipted. A changed middleware cap must have its prior memory/swap/policy saved and restored during the R2 rollback drill.

Close gateway/API producers first, retain worker and runner while original file operations drain, then stop worker, runner and dependencies. Use protected scan-admission maintenance mode when freezing. UNKNOWN blocks a snapshot; never clear operation state or a lease to make backup pass.

Use a consistent cold backup of PostgreSQL, SeaweedFS metadata/volume, workspace XFS, Rabbit definitions and key versions. A compressed XFS backup is valid only after its complete decompressed bytes/hash are checked. Only an independent restore copy receives a new UUID. Source mount and UUID remain fixed.

Before RELEASED: actual HTTP Host/IP smoke through the approved encrypted login tunnel, bounded production observation, independent serial restore, R2 rollback and same-R3 restart. No real provider requests. Production tag only follows those receipts. GitHub Actions stay disabled. Missing checks remain NOT_RUN.

## Final functional release evidence (2026-10-26)

See [acceptance](r3.4-acceptance.json) and [evidence index](../evidence/r34/evidence-index.json). Independent serial restore, original R2 rollback and same immutable R3 restart passed. Final authenticated read-only smoke passed; three immutable versions match their hashes. Actual profile includes seven retained dependencies and four R3 app services, with2880MiB including sandbox/runtime/helper reserves. The R3-aware [switch helper](scripts/platform-switch.py) uses a host release-selection manifest and exact retained identities; its legacy direction was dry-run only. Never assume the old ten-service R2 selection.

The earlier1806.66s resource/readiness sample is retained with its original source/config scope. After the narrow query/config correction, targeted real PG/MQ regression, actual restart/upload and independent recovery were repeated. A second30min mixed window and extended crash combinations are NOT_RUN under the user-approved functional-first closure; historical failures are not rewritten. No supplier requests, DNS changes, global cleanup or UNKNOWN replay occurred.
