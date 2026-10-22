# Design

## Accepted baseline and framework ownership
Base main ff16819fec83c5eb27bb936a41552df61032d24b; phase synced by fast-forward before step creation. Java21/Boot4.0.8/AgentScope2.0.4/docker-java3.7.1 and existing Nimbus/Spring AMQP/MyBatis remain locked. No extra Agent loop or JDBC executor.

App owns environment prepare/read/retry/idle-stop/reconciliation; domain pure Java EnvironmentRepository/SandboxRuntime/FileAccess ports; infrastructure typed DAO/PO/XML and docker-java adapters; app packages XML and short TransactionTemplate operations; trigger thin controllers; frontend generated clients/Query with scoped keys.

## Lifecycle and transactions
Storage READY is independent of NOT_CREATED/PREPARING/READY/STOPPED/ERROR/BLOCKED environment. GET absent environment has revision0 and never creates a row. POST empty object requires workspace storage READY, If-Match environment revision and Idempotency-Key. Same key/body returns original stable operation; conflicting body409. Only explicit FAILED/NOT_STARTED accepts retry. STOPPED can prepare a new generation only after durable exact stop and no outstanding use.

V11 adds workspace_environment, environment_operation/outbox/inbox/idempotency, runner environment command/receipt, singleton sandbox capacity slot, exclusive environment_use and stop receipt. Composite space/workspace/environment constraints, unique command and operation, generation/fence/revision/hash checks. Additive widening of workspace.environment_state CHECK mirrors committed environment state; initial migration leaves existing workspaces NOT_CREATED. Fixed lock order slot→workspace→environment/operation→command/use/receipt. DB time60s lease/10s renewal; live actor authorization and owner/gen/fence rechecked before commit. No remote I/O in transactions. Single active sandbox slot retains unknown/stopping owners; expiry does not prove stopped.

Spring AMQP confirmed mandatory publications and committed claim before manual ACK, concurrency/prefetch1. Existing lifecycle factory/pattern reused; separate versioned environment routing queue avoids decoding v1 as v2. Worker invokes one signed POST, then only signed GET for the same command. Idle/revoke sweeps persist STOP commands through outbox; no DB direct execution fallback. Existing business task slot remains1, not replaced by this resource slot.

## Runner protocol and durable effects
Separate internal /internal/environment-operations endpoints and ENVIRONMENT_V2 envelopes bind method/path/raw body hash, kind, environment generation, fixed policy/image identity, actor/workspace/operation/command/owner/fence/deadline. Existing Nimbus RS256 keys remain separate and immutable. Binding only gains exact allowlisted new paths; old v1 paths/semantics preserved. Result signed with command/result hash/revision. Runner role can access only required environment facts plus existing authorize function, not chat/key/SDK state.

Create intent is committed before Docker create. Container name/labels include exact environment UUID/generation/command and image/policy hash. Existing physical intent is inspected and never blindly restarted. CREATED-before-start, STARTED-before-receipt and missing/unreachable daemon remain independently observable; uncertain effects retain BLOCKED/slot. Only definite pre-effect rejection can return ERROR and free unused slot. Physical deletion follows committed stop receipt.

## Runtime policy and file bridge
Only fixed configured runtime runsc and officially pinned20261005.0 systrap; validate daemon runtime path/runtimeArgs and binary identity through trusted bounded attestation, image/config/inspect, never dmesg. API/worker have no Docker socket, runner has no public port. Sandbox UID/GID10001, readonly root, cap ALL dropped, no-new-privileges, network none, no devices/socket/secrets,512MiB memory=swap,1CPU,PID128,tmpfs64MiB. Fixed workspace UUID path only; existing quota helper independently verifies mountUUID/prjquota/project/ownership and uses its existing exact read-only loop mapping, never given to sandbox.

Prepare succeeds only after bounded real sandbox fixed canary and host/guest same-data hash verification. File helper is fixed image code, uses dir_fd/O_NOFOLLOW per component and regular-file/no-hardlink checks; rejects absolute/.. /backslash/NUL/symlink/device paths. Operations are fixed READ/WRITE/LIST, accepted only under scoped use lease. No arbitrary host command, shell script, path mount or runtime args in HTTP. Private test-only probes are enumerated Python666/NodeCrossRead/failure/cancellation fixtures. This is not production ToolBase registration, file history or object assets.

## Completion, cancellation and idle
Each use owns a durable ID/gen/fence. Runner revalidates before effects and result commit; bounded liveness watcher denies new work and stops exact sandbox when authority/DB unavailable. execCreate/execStart/inspectExec;120s total timeout, stdout+stderr64KiB bounded/truncated, no unbounded queue. Future/callback/HTTP close alone is not stopped. Stop exact container, verify exec/container non-running and callbacks/timers terminated; missing/unreachable inspect is not proof. Uncertain stop retains slot/use and BLOCKED until exact trusted host stop evidence. Idle60 with no active use persists STOP via outbox, stores stop receipt then frees slot/removes exact stopped container; files/project quota retained.

## UI and acceptance
Three public endpoints prepare/get/retry only. Detail distinguishes storage/environment, stable error/actions, READY idle-release explanation; no terminal/file/upload/wake/delete. Real browser through actual API; native positive via private SSH tunnel with newly locally generated fixture credentials, no production credential export. Initial cap2016 services +512sandbox+128runtime+64helper=2720MiB; two-consumer profile stops API/gateway first, remains<=2887. Host>=768MiB/free root>=5GiB; all operator resources counted and builds serial. Read existing operations lessons before execution, fixed scripts before long observation, separate raw failure records. Cloud tests use independent root/DB/XFS/network and bounded finally/watchdog; restore exact R2 V9 and legacy stopped.

## R3.2维护前磁盘准入
只读现场根盘9,225,768,960字节，不能再实分配4GiB后保持5GiB余量。仅可对已卸载/loop脱离的确切R3.1测试XFS做无损冷归档：锁定运维锁、核验路径/UUID/停机身份，流式压缩并逐字节SHA校验两份保护副本，记录原所有权/模式及恢复命令后才释放原预分配文件。旧测试数据保留可恢复，Docker卷/镜像、生产XFS/数据库和旧平台不动，无全局清理/稀疏冒充/复用旧数据；新R3.2仍独立4GiB实分配和UUID。任何守卫或准入不满足即停止云端窗口，继续独立本地工作。

## Fixed file transport framing
The protected bridge uses one UTF8 JSONL frame with newline, at most64KiB. docker-java streaming is not assumed to half-close stdin. Missing terminator and oversized frames are refused; no arbitrary program/endpoint is added. Policy identity is bridge-v2-jsonl, so old inputs are not hot-reused.
