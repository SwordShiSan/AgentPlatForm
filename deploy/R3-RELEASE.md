# R3 release operations

The accepted runtime is fd2aa8a; deployment grant/test evidence is recorded separately. Keep R2 containers and their original settings for rollback. Existing production PostgreSQL, RabbitMQ and object data are reused serially; never import acceptance accounts, passwords or files.

Production filer uses PostgreSQL metadata. Do not copy the acceptance LevelDB filer configuration. Preserve existing model encryption/cursor keys. Create independent production runner/control signatures and narrow runner/file credentials. Runner alone receives the Docker socket; no public runner port.

Resource allocation can change within2887MiB including sandbox512, runtime128 and helper64. Actual values and exact container IDs must be receipted. A changed middleware cap must have its prior memory/swap/policy saved and restored during the R2 rollback drill.

Close gateway/API producers first, retain worker and runner while original file operations drain, then stop worker, runner and dependencies. Use protected scan-admission maintenance mode when freezing. UNKNOWN blocks a snapshot; never clear operation state or a lease to make backup pass.

Use a consistent cold backup of PostgreSQL, SeaweedFS metadata/volume, workspace XFS, Rabbit definitions and key versions. A compressed XFS backup is valid only after its complete decompressed bytes/hash are checked. Only an independent restore copy receives a new UUID. Source mount and UUID remain fixed.

Before RELEASED: actual HTTP Host/IP smoke through the approved encrypted login tunnel, bounded production observation, independent serial restore, R2 rollback and same-R3 restart. No real provider requests. Production tag only follows those receipts. GitHub Actions stay disabled. Missing checks remain NOT_RUN.
