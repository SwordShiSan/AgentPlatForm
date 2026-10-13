# Accepted Plan and Architecture

Actual Plan mode completed and user requested implementation on 2026-10-13. Base a6d7d5d; branch step/r0.3-storage-deploy-baseline. Canonical R0.3 formal plan records the accepted minimal-host budgets. Cloud developer acceptance replaces mandatory workstation Docker acceptance, not the distinct production RELEASED gate.

## Operational sequence

Inventory before mutation; snapshot restart policies, mount/image identities and recent diagnostics; verify a restorable database backup. Preserve backend6.133/6.132, frontend4.79/4.78 and all referenced middleware images/volumes. Remove only inventoried obsolete unreferenced application image tags and confirmed unused deployment archives. Rotate journald natively to256MiB/7days and rsyslog messages64MiB/4 compressed generations; do not truncate Docker log files. Pause gateway/apps then dependencies after saving restart policies and setting no to prevent daemon restart from reactivating legacy containers. Restore dependencies then apps/ingress with original policies on failure and after acceptance. No global prune or down -v.

## Resource envelope

Container MiB caps: api320/worker384/runner192/egress128; postgres320/rabbitmq256/redis64/gateway32; SeaweedFS master64/volume96/filer96/S364. Sandbox512 plus128 host runtime allowance. One active sandbox, CPU1/pids128/tmpfs64MiB/deadline120s; no sandbox swap. JVM heaps leave native headroom. No cloud compilation or permanent full observability stack. Budgets are initial hypotheses; require measured30-minute acceptance and no host OOM. Saturation queues work; no overcommit or silent security downgrade.

## Persistence and ports

Domain ports use pure Java/types, infrastructure owns JDBC/AMQP/S3. Single migration job initializes independent pf/pf_runtime and SeaweedFS metadata schema/role. Runtime state uses scoped atomic SQL CAS; probe-specific transaction/dispatch fixtures do not introduce a second scheduler or claim R2 production execution is complete. AMQP vhost/topology/envelope follow MQ-1; mandatory+confirm precede publish success; inbox and claimed fence commit before ACK. S3 keys follow UD-1, private bucket, own SHA256, no ETag hash assumptions. Jackson2 SDK codec is private; Jackson3 HTTP remains separate.

Readiness GET /api/v1/readiness returns only READY or NOT_READY, HTTP200/503, no hosts/credentials; /api/v1/health retains shell-only semantics. Dependency observations and bounded metrics are internal. API/worker may connect to DB, runner/egress do not; only trusted runner management holds socket access. Profile-specific dependency wiring prevents unrelated clients from increasing process RSS.

## Sandbox and filesystem

Install pinned verified runsc binary; register explicit runsc/systrap without changing default runtime. Merge daemon configuration, keep rollback copy, restart only inside the authorized legacy pause window. Nonroot sandbox, cap-drop ALL, no-new-privileges, rootfs read-only, default network none, no socket/host secrets, selected workspace mount only. Admission checks host MemAvailable and active slot. Idle60s after no active operations destroys container, preserves files. Failed/unknown effects are not replayed. Runtime failure disables shell rather than falling back to runc.

Preallocate4GiB /srv/agentplatform/quota/workspaces.xfs, format only after explicit new-file checks, mount with loop/prjquota at /srv/agentplatform/workspaces. UUID and mount verification before any workspace I/O; mount absent never writes underlay. One2GiB workspace,100000inode limit; root disk backing is documented, not an independent failure domain. Test actual native/guest project-quota byte/inode denial, remount/rebuild persistence. Do not remount root or delete old filesystem data.

Actual boundary correction (2026-10-13): Native Linux6.6 XFS and gVisor both returned ENOSPC at project quota exhaustion (kernel XFS_QMOPT_ENOSPC is used by pquota). Preserve native-host bounded denial proof with an unprivileged UID, then require guest bounded denial, project usage/limit evidence and plenty of root free space; record native28/guest28 instead of assuming errno identity across the VFS. Future runner maps quota after authoritative project recheck, never blindly equates arbitrary ENOSPC with quota. No limit or fail-closed requirement is weakened. RabbitMQ memory alarm128MB blocked confirms at measured~137MiB; raise alarm to192MB within unchanged256MiB cap and rerun actual confirm gate. Filer requires explicit createTable/C collation and unbounded max-open pool to avoid nested listing deadlock; containers and PostgreSQL total connections remain bounded.

## UI, migration and rollback

tokens.css controls colors/spacing/focus/touch targets, light/dark/system with local pre-login UI preference only; account persistence waits R1. Health, not-found, forbidden, offline and internal design demonstration routes use common feedback. No fake task/workspace buttons. Additive Flyway only, feature flags false, schema mismatch refuses readiness. New deployment stays loopback18080/SSH tunnel; old public domain/TLS unchanged. Failure stops only new projects, retains data and restores old snapshots.
