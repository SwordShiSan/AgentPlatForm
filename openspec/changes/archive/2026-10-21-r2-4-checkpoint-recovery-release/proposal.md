## Why
R2.3 proves cancellation and stop accounting, but production ChatExecution still rejects restoration. Attempt-local turn numbering, independently saved SDK state/checkpoints and automatic unsent-task redispatch do not yet provide manual durable checkpoint continuation. R2 must demonstrate actual process recovery and retain R1 compatibility before release.

## What Changes
- Add task-wide execution identity, immutable checkpoint v2 safe points and fenced manual recovery through the existing outbox/MQ path.
- Reuse AgentScope2.0.4 state/Harness APIs, MyBatis/Spring short transactions and Spring AMQP; preserve UNKNOWN holds and single-slot ownership.
- Add an authorized read-only recovery projection and Vue feedback; operator-only recovery commands.
- Complete isolated real dependency/browser/process tests, exact-image acceptance, R1 rollback compatibility and the R2 cloud release window.

## Capabilities
### New Capabilities
- `checkpoint-recovery-release`: committed-safe-point restoration with operational authorization and evidence-based release.

## Impact
Additive Flyway V9; V1–V8 and runtime schema1 unchanged. New runtime bundle; no live upgrade of old bundles. No production tools/shell/compaction or public recovery mutation. GitHub Actions remain disabled. Local verification precedes protected two-level PRs and cloud deployment.
