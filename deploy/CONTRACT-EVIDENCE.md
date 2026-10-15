# R0.4 contract and framework evidence

Implemented HTTP baseline: `GET /api/v1/health`, `GET /api/v1/readiness`, Problem and the versioned event envelope. Readiness is minimal200/503; dependency details/metrics remain internal. Envelope payloads, production producers, models, identity, scheduling and business shell are not delivered by this stage. `contracts/r0-baseline.lock.json` binds8 schema/generated/HC-1 files; re-generation and illegal fixtures pass without drift.

SDK: AgentScope Java2.0.4, official tag `v2.0.4`, commit `0b6743b263ba8e39eb417e35048026f1e0196548` ([source](https://github.com/agentscope-ai/agentscope-java/tree/0b6743b263ba8e39eb417e35048026f1e0196548)). Local source checkout is clean. Actual resolved JAR hashes are in the130-artifact lock. Fresh `javap` output for HarnessAgent, ToolBase, AgentStateStore and MiddlewareBase is protected evidence, not an inferred API description.

| HC-1 boundary | Actual SDK/adapter surface | Executable assertion |
|---|---|---|
| LoopDriver / PreparedRun | `prepareCall(List<Msg>, RuntimeContext)`, `AgentRun`, `streamEvents`, `interrupt` | SdkSurfaceProbeTest verifies deferred subscription/events; AgentScopeContractProbeTest verifies cancellation before/during model |
| ModelGateway / LoopBoundary / LedgerPort | Real HarnessAgent with middleware around model/tool boundaries | Full call batch persisted before two effects; delayed/failed round has zero effect; failed result stops next call |
| PolicyPort / ToolExecutionPort / ToolCatalogPort | Guarded tool definitions/execution; adapter-only approval frame | Plan/revocation/deny stop writes; modified/duplicate approval rejected; IDs survive real JVM approval restore |
| RuntimeStatePort / StateStorage | `AgentStateStore` bridge, scoped private Jackson2 JSON, platform CAS | Four actual JVM crash windows, UNKNOWN not replayed, one CAS winner, cross-space denial, corrupt state/manifest rejection |
| ContextAssembler / MemoryPort / RetrievalPort | Explicit platform ports before the single SDK loop | HarnessComponentContractTest swaps three scripted components, cancels both recall futures and prevents reference facts/cross-space cancellation forgery |
| CompletionPolicy | Platform completion evidence gate | Missing evidence/repair is not SUCCESS; no second outer ReAct loop |
| HTTP serialization | Jackson3 HTTP boundary, isolated SDK Jackson2 | ProviderAndStateProbeTest reads the old2.21.1 state fixture with scope/manifest/version/CAS assertions; app HTTP tests check the independent boundary |

Current regression evidence:44 host tests,39 offline non-root Linux SDK/architecture tests,3 real PostgreSQL Java tests,15 Vue tests and4 real-API browser scenarios. These are distinct evidence classes. The OpenAI extension uses a real loopback SSE protocol fixture, not a live vendor model. Framework state/file models do not claim production orchestration or storage behavior; actual PG/MQ/S3 probes are separate.

## Accepted prior deviations and ownership

| Decision | Reason and regression | Later owner |
|---|---|---|
| SDK retries use1, not0 | SDK rejects0; offline surface/provider tests verify one physical attempt | R2 execution integration |
| Approval uses metadata-only TOOL control | SDK rejects caller SYSTEM input; approved control is excluded from user/history/model facts and verified across JVM restore | R2 approval/operation ledger |
| SQL CAS belongs to the platform | Durable commit/scope/version authority stays outside SDK; actual PostgreSQL CAS/rollback tests supplement file probes | R2 durable execution |
| Linux XFS project quota returns ENOSPC | Native/guest tests verify limits and global free space; do not require user-quota EDQUOT | R3 workspace |
| Cloud Linux owns runtime/quota/resource gates | Docker Desktop validates app/service compatibility; cloud proves host mount/runtime and minimum-memory behavior | R3 runtime, R9 release |
| Docker Desktop ID differs from exported config | Match platform, layer diff IDs and normalized running config; cloud inspects exact exported config ID | Deployment/R9 provenance |
| Official vendor findings are informational here | User-approved scope2026-10-15; raw findings retained, no claim that they were fixed | Revisit only on project functional/isolation impact |

STS denial is tested at the signed HTTP boundary. SSE-S3 business encryption is not delivered; retaining a wrapping-password/key-version backup does not establish encryption support. R0.4 remains unverified until observation, isolated restore, legacy recovery and two-level PR promotion complete; R0 remains unreleased.
