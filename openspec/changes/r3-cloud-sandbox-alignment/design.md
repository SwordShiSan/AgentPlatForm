> **DEFERRED / 保留方案，暂不实施（2026-10-26）**
> 用户因成本决定继续现行本地Docker＋gVisor/runsc、XFS、受信文件helper和SeaweedFS。本文件下方云方案仅保留研究，**非当前实施项、非R4前置**；不应用、不归档合并到主规格，实施任务保持未完成，云功能验收保持NOT_RUN。云专用2784MiB重分配与V15候选不生效，迁移号不预占；本地完整2880MiB基线继续计入sandbox/runtime/helper。

## Context
Current stable source is02bad812753987d7b3aa09667d666c66055eba94; production remains production-r3-95ffaf3b7137. R3 is RELEASED and R4 has not started. This is a planning change, not a claim that cloud execution is implemented.

SandboxRuntime and V11/V14 currently require local projectId, Docker digest/containerId and host stop evidence. Current file authority is XFS with project quota; S3 contains immutable versions. A cloud instance cannot bind the Tencent host directory.

## Goals / Non-Goals
Goals: preserve all delivered platform functions while replacing local business execution; retain one Harness, one queue handoff and one execution slot; keep authoritative files, version conflict behavior, quotas, cancellation and UNKNOWN semantics.

Non-goals: changing model integration, moving business databases, introducing Kubernetes or another agent loop, maintaining patched third-party SDK source, public arbitrary shell/PTY, file edit/delete/restore, R4 implementation or production changes in this turn.

## Decisions
1. Use the Alibaba-maintained E2B Java data-plane SDK, candidate com.alibaba.serverless:e2b-java-sdk:3.0.7 at official commit98d960b7239a78ccbe580dd766e28db3fc4f325d. Public Maven release was not established; reproducibly package unmodified official source and lock source/JAR/dependencies before use. Native sandbox20260820 is a different control-plane SDK with AK/STS, not a substitute for an API Key.
2. Implement AliyunSandboxRuntimeAdapter under pure-Java provider-neutral ports; SDK objects never enter domain or public HTTP. API/worker use the existing internal RS256 runner boundary; runner uses provider HTTPS/SDK authentication. A new ENVIRONMENT_CLOUD_V1 protocol binds provider/region/template/version/policy/sandboxId/operation/generation/fence. Keep historical V2/V3 queries.
3. Preserve XFS/current files and S3/immutable versions. WorkspaceSynchronizationService freezes a canonical input manifest under exclusive lease, transfers bounded data, verifies the remote copy, executes, confirms writers stopped, collects bounded output and uses current FileRuntime atomic per-file replacement/version sealing. Cloud filesystem is a working copy. Multi-file collection is a tracked batch with per-file receipts, not an invented atomic transaction. Every public file commit advances the file watermark and invalidates old cloud input; execution and sleep collection recheck the current watermark/baseVersion, synchronize or reject conflict instead of overwriting newer uploads.
4. Use explicit synchronization and termination for sleep/delete; wake creates a new generation from persistent files. Do not require provider pause/snapshot or enable auto-resume. Listing/status does not call connect. A local runner shutdown proves nothing about remote termination. Admit remaining TTL for execution plus worst-case collection/retries, including full2GiB/100000-file workloads; confirm renewals, stop admission and collect early when renewal fails. Auto-expiry must not be reported as successful collection.
5. Add V15 provider binding, physical-operation facts, sync manifests/receipts and cloud usage references without modifying old migrations or faking Docker IDs. Prefer separate provider-specific binding/operation facts with old Docker rows retained historically; do not insert cloud READY identities into Docker-only columns. Exact DDL follows mandatory P0 capability results. No two active write/execution paths for a generation.
6. Keep MyBatis typed Mapper/PO/XML, TransactionTemplate, outbox/confirmed Spring AMQP/manual ACK. Lock quota -> capacity -> workspace -> environment/operation -> command/receipt. Do not hold a DB transaction over network/Docker/file transfer. Check authorization and database-time lease/fence before effects and commit.
7. Remove local512MiB sandbox and128MiB runtime reserve only after cutover proves local business execution stopped. Proposed local ceilings: API512,worker512,runner384,PG320,MQ256,Redis64,gateway32,Seaweed640,shared trusted helper64=2784MiB. Dynamic adjustment remains allowed within2887MiB with768MiB host reserve and5GiB disk margin. Provider instance memory/cost is separate; SDK buffers, thread pools and helper still count locally.

## Risks / Trade-offs
- SDK InputStream/readBytes and command output buffer entire payloads -> P0 tests bounded <=8MiB SDK chunks with fixed trusted assembly or supported streaming/download URLs; do not pass100MiB to whole-file SDK methods or modify SDK source. Java uploadUrl API is not confirmed and must not be invented from TypeScript examples.
- Rootfs is temporary and provider kill may destroy unsynchronized output -> first prove writer quiescence and collect/verify output, then terminate; otherwise retain BLOCKED and durable unsynchronized-data facts.
- Cloud network and PID/quota enforcement differ from runsc/cgroups -> freeze actual template/runtime/QoS and verify required isolation with provider evidence, not copied local assertions. Failure blocks that capability, not identity/chat/file access.
- Create/exec response loss may lack provider idempotency -> persist intent and query exact resource/operation metadata; unresolved work remains BLOCKED, never implicitly re-created/replayed.
- Files may need temporary duplicate storage -> admit full input/output/staging peak and object reservations. Remote root disk size does not establish the platform2GiB/100000inode hard limit.
- Economy scheduling variability and billing -> one active instance, explicit TTL, idle60s persistent teardown, separate cost estimates/unknown holds. No new default concurrency.
- Existing R3 runner OOM cause is unresolved -> retain historical evidence; higher budget and provider migration do not prove a root-cause fix.

## Migration Plan
P0 fixed official SDK and real capability probes -> P1 V15/provider identity/persistent handoff -> P2 manifest sync and file compatibility -> P3 lifecycle and browser -> P4 focused/full integration, protected two-level promotion and separate production cutover.

Keep current R3 immutable runtime/consistent backup. Drain writes and old local environments, preserve data and final manifest, then switch selected provider for new generations. Never use cloud outage as local fallback. Explicit rollback first reconciles remote operations/data/termination; do not run two providers or write copies concurrently. Old R3 cannot interpret cloud READY rows: verify isolated cloud facts and an explicit environment-maintenance admission switch/compatible projection before promotion; restart old R3 for identity/chat/files only until a real local generation is explicitly restored. Failed old-program reads block release; do not fake Docker identities or restore an old DB over newer business writes. Older R2 rollback containers were removed by authorized cleanup; use current R3 rollback only. Keep production tag unchanged until a separately accepted immutable release.

## Open Questions
To resolve before implementation reaches the corresponding batch: actual Team/QoS/runtime generation/template version; reproducible SDK artifact; safe100MiB transfer/output cap; remote command and instance terminal facts; work-directory hard quota; provider operation discovery/idempotency; supported create-time network flags and SDK/client behavior. Existing instance read-only GET succeeded but none of these capability gates is PASS yet.

## Canonical documentation
Full scope, Chinese architecture, source mapping, sources and stage-specific constraints live only in the canonical external reconstruction directory. Current main specs and archived tests remain historical until this change is actually implemented/verified and archived.
