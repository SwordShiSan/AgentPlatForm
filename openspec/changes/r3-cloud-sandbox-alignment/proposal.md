> **DEFERRED / 保留方案，暂不实施（2026-10-26）**
> 用户因成本决定继续现行本地Docker＋gVisor/runsc、XFS、受信文件helper和SeaweedFS。本文件下方云方案仅保留研究，**非当前实施项、非R4前置**；不应用、不归档合并到主规格，实施任务保持未完成，云功能验收保持NOT_RUN。云专用2784MiB重分配与V15候选不生效，迁移号不预占；本地完整2880MiB基线继续计入sandbox/runtime/helper。

## Why
The user selected Alibaba Cloud Agent Sandbox Speed Edition Economy in cn-hangzhou instead of local business sandboxes. Existing R3 files, versions, quotas and lifecycle must remain complete while the execution provider changes; direct substitution would break Docker-specific identity and shared-XFS assumptions.

## What Changes
- Plan a provider-neutral execution binding and an official-team Java SDK adapter, with additive V15 and versioned internal cloud commands. Preserve Java21, Boot4.0.8, AgentScope2.0.4, six modules, MyBatis/Spring transactions, Spring AMQP and runtime baseline1.
- Preserve XFS current-file authority, private S3 immutable versions and existing file APIs; add durable manifest-based input/output synchronization under the original workspace lease and version/fence guards.
- Preserve product sleep/wake/delete semantics through synchronize/terminate/rebuild; do not depend on provider pause/snapshot. Distinguish remote stopping from local transport cancellation.
- Move business execution memory to the cloud; reallocate local ceilings to a proposed2784MiB full profile within2887MiB/768MiB host reserve, counting SDK buffers and trusted storage helpers.
- Make this R3.C correction a prerequisite to R4. No implementation, cloud mutation, production switch or real model call is authorized by this planning artifact alone.

## Capabilities
### New Capabilities
- `cloud-sandbox-alignment`: official Java SDK/version/capability gates, cloud identity, synchronized workspace copies, provider facts, migration and separate resource/cost accounting.
### Modified Capabilities
- `sandbox-data-plane`: replace future local execution assumptions with cloud-provider policy/evidence while retaining leases, bounded IO and trusted storage adapters.
- `environment-lifecycle-release`: remote lifecycle mapping, signed protocol compatibility and split host/cloud budgets.
- `storage-deployment-baseline`: replace mandatory local-gVisor execution profile with fail-closed selected-provider isolation; retain XFS hard quotas for authoritative files.
- `file-assets-quota`: preserve sandbox-independent files and extend the bounded data plane with versioned synchronization.
- `reconstruction-governance`: record this correction before R4 without rewriting historical VERIFIED evidence.

## Impact
Future changes cover domain ports, app lifecycle/synchronization, infrastructure SDK/provider and Mapper/XML, internal signed commands, provider-neutral V15 bindings, generated contracts/UI projections and deployment selection. Public endpoint compatibility remains required. Current productionR3 and all V1-V14 bytes remain unchanged during planning. The canonical Chinese plan is outside this repository at `项目文档/06-AgentScope重构专项/07-交付与验收/阶段/R3.C-阿里云沙箱与数据面迁移纠正计划.md`; Git delivery does not deliver that body.
