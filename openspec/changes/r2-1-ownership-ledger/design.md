## Decisions

Approved Plan: R2.1 execution ownership and durable ledger, 2026-10-18; base main 9f780fa. Phase `phase/r2-task-recovery`, step `step/r2.1-ownership-ledger`. Local acceptance only; current cloud R1 release is untouched.

V6 adds scoped conversation leases, logical model turns, tool operations, immutable checkpoints and trusted stopped-owner evidence. Keep runtime baseline schema1 distinct from Flyway version6. No modification to V1–V5 and no synthetic backfill of historical rounds.

Guard mutation transactions with global slot → conversation → task/dispatch → attempt → call/ledger/budget locks. Validate DB-time lease60s, heartbeat10s, generation, owner, fence and live identity. Failed ownership must roll back all writes including private SDK state. Keep RabbitMQ as the only delivery route; commit claim/inbox before ACK.

Assign logical turn before physical calls; bind accepted response to that turn and its call. Defer successful settlement to `afterModelCommitted`, atomically write full normalized round and all tool declarations with accounting/events. Failed/retried physical requests remain separately recorded. Side-effect uncertainty retains UNKNOWN and budget holds.

SDK state remains private, attempt-namespaced and CAS-protected. Persist state at safe boundaries, then seal immutable bytes, canonical watermark and exact bundle/codec/component manifest under the same owner guard. Restore validates every reference/hash before copying into another attempt. Canonical facts override SDK control state; missing/incompatible state blocks.

Trusted host operator records exact container/owner/image/start identity, stops it and verifies stopped state and expired lease. Only operator privileges can submit the resulting proof. Bare boolean confirmation is rejected. Definitely unstarted work is requeued through outbox; completed terminal rounds may converge; STARTED unknown work stays BLOCKED. No Docker socket in applications.

Internal deterministic tools exercise ledger boundaries only. Full user tools remain R3; replay/reconciliation UI remains R2.3; complete process continuation and real model demo remain R2.4. Preserve public APIs and HC-1 compatibility via additive adapters.

## Validation and rollback

Use independent `agentplatform-r21-local`, separate database/volumes/network/credentials and collision-checked loopback ports. Initial caps2528MiB (API512, workers2×512, PG320, MQ512, Redis64, gateway32, provider64), at most2887MiB. No real provider calls, no cloud rollout. Build and bounded runtime run serially.

Validate fresh V6/V5 upgrade, R1 regressions, two-worker contention, ACK/confirm loss, crash windows, stale fences including SDK CAS, live revocation, UNKNOWN reservations, state corruption and proof rejection. Additive rollback preserves data; do not run old workers against newly active R2 ownership. Incomplete gates remain NOT_RUN/FAIL, no PR promotion until complete.

## SDK safe-point implementation evidence

Real PG/SDK test found STATE_CAS_CONFLICT when public saveAgentState was invoked inside an active SDK call: ReActAgent CallExecution.loadedVersion and cache slotVersions are separate. Platform code therefore snapshots a private AgentState clone into checkpoint_agent_state using private Jackson2, overlays canonical ledger history, and seals it as immutable agent_state bytes. The live SDK agent_state CAS is unchanged; no third-party source changes or unconditional overwrite. The manifest binds state source keys, versions, byte hashes, canonical hash and watermark. Legacy HC-1 adapters keep their final seal behavior.

Stop proofs are HMAC-SHA256 authenticated by an independent 32-byte protected host/operator key. API/worker never receive it; runtime DB roles cannot insert stopped_owner_proof. Host capture binds owner ready marker and active attempt before stopping the exact container, re-inspects the start identity and signs only after lease expiry. Controlled fixture-only pause points allow host-driven SIGKILL crash-window evidence; no public fault endpoint.

Revocation cleanup may release unsent reservations and mark failure under the matching owner fence, but cannot publish accepted content, launch calls or modify private SDK state after authorization loss.

New admissions pin bundle0199e000-0000-7000-8000-000000000004 with loop agentscope-stream-ledger-v1. Existing frozen bundles are never rewritten: unsupported queued bundles become BLOCKED/BUNDLE_INCOMPATIBLE before any call and release unspent admission holds. Historical conversations remain readable. No legacy bundle is mislabeled checkpoint-capable.

## Local broker health tuning

During local build/probe/export load, the initial RabbitMQ0.5CPU health CLI repeatedly exceeded5s. Cgroup evidence showed no OOM (memory about110MiB) and heavy CPU throttling; this is not a component/source defect. R2.1 uses1CPU, bounded CLI schedulers and disabled scheduler busy-wait, retains512MiB, and keeps the actual rabbitmq-diagnostics ping with10s timeout/15s interval. Validate AMQP confirm/consume and healthy recovery before runtime acceptance. No cloud or R1 deployment configuration changes.

## Reclaimed entity versions

The first immutable candidate passed all six OS crash windows, but review found claim events still used the R1 first-claim constants. Reclaimed task versions are higher, so this could temporarily hide RUNNING in the existing browser reducer. Emit the actual task/message row versions, add a real-PG assertion and refresh-during-run browser coverage, then rebuild/retest the final API candidate. This preserves wire shapes and does not implement R2.2's new snapshot/event facilities. Prior candidate evidence is historical, never substituted for final-runtime gates.

Within each worker, serialize the full claim/ACK/run callback on its execution instance across AMQP channel reconnects. This prevents old callback cleanup from touching the next run's active pointer after the previous database slot is cleared. A two-thread handoff test holds the first execution in cleanup and verifies the second callback cannot durably claim until it exits. Database ownership remains the cross-worker authority.
