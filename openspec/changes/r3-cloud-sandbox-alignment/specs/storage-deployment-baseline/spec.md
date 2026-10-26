> **DEFERRED / 保留方案，暂不实施（2026-10-26）**
> 用户因成本决定继续现行本地Docker＋gVisor/runsc、XFS、受信文件helper和SeaweedFS。本文件下方云方案仅保留研究，**非当前实施项、非R4前置**；不应用、不归档合并到主规格，实施任务保持未完成，云功能验收保持NOT_RUN。云专用2784MiB重分配与V15候选不生效，迁移号不预占；本地完整2880MiB基线继续计入sandbox/runtime/helper。

## MODIFIED Requirements

### Requirement: Bounded fail-closed sandbox
The platform SHALL limit execution to one on-demand verified selected-provider sandbox and enforce scoped files, access/resource policies, host/provider admission and idle60second lifecycle without deleting authoritative files. The historical local profile uses gVisor/systrap; R3.C targets Alibaba Speed Edition Economy through the Java SDK and SHALL NOT silently fall back to local execution. SDK/local buffers remain within host memory admission; cloud resource memory is separately budgeted.

#### Scenario: Runtime missing or budget exhausted
- **WHEN** the selected provider/policy is unavailable or its required resources cannot be admitted
- **THEN** shell is disabled or work is queued, never silently executed through a local provider or uncontrolled swap

#### Scenario: Exhaustion or idle
- **WHEN** a sandbox exhausts its limits or becomes idle without pending operations
- **THEN** platform services remain alive, unknown effects are not replayed and idle teardown preserves workspace content
