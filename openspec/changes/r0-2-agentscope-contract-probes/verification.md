# R0.2 verification

Plan approved; IMPLEMENTING. Baseline c464a5b; SDK2.0.4 source0b6743b. Host backend verify passed 38 cases (29 SDK/state + 9 app/architecture/HTTP), exit0; frontend contracts/typecheck/15 unit tests/build passed. Linux/UI and promotion results are recorded below after actual execution.

| Case group | Spec / canonical cases | Expected evidence | Result |
|---|---|---|---|
| P02-STRUCTURE | Versioned pure composition; HC01/02/05 | Java architecture checks, deterministic replacement fixtures, no SDK in domain | HOST PASS |
| P02-SDK | Real framework acceptance; EX01 | actual signatures, stream/prepareCall/interrupt, provider loopback wire fixtures, Jackson2/3 | HOST PASS |
| P02-COMMIT | Commit before effect; EX07-4 | full batch before counters, delayed/failed commit zero effect, malformed/length/abort/empty | HOST PASS |
| P02-POLICY | Guarded decisions; HC03 | DEFAULT, ASK/deny/Plan/revocation/cancel; precise metadata-only SDK control absent from history/model | HOST PASS |
| P02-RECOVERY | Ledger-first restoration; HC04/07/EX07-6 | new JVM after four crash points, original IDs/model count, manifest/CAS/isolation/corruption | HOST PASS |
| P02-AUTHORITY | Invalid composition; HC06/EX07-3 | disabled defaults, bounded IO footprint, duplicate authorities rejected | HOST PASS |
| P02-STOP | Evidence before completion | PASS/REPAIR/INCOMPLETE and no unverified success or second outer loop | HOST PASS |
| P02-LINUX | Real framework acceptance | offline non-root Docker JUnit case reports and existing 3 UI cases | PASS: Linux36/UI3, exit0 |
| P02-PROMOTION | Real framework acceptance | required CI, two PRs, SHAs, main/phase sync, canonical ledger | NOT_RUN |

Record individual parameterized cases, command/exit, model/tool counts, bundle/state/operation hashes, JVM crash/restart facts and Docker image IDs. Real supplier/production PG/MQ/S3/runner/XFS/cloud remain outside this stage. A mandatory unsupported safety boundary requires an ADR stop, not a weakened assertion.

## Host commands and exact case mapping

- backend `mvnw.cmd -B -ntp verify dependency:copy-dependencies -DincludeScope=test -DoutputDirectory=target/probe-libs`: 38 tests, zero failures/errors/skips, exit0. Log: evidence/r0.2-final-backend.log; exact per-case results in infrastructure/app target/surefire-reports (ignored).
- AgentScopeContractProbeTest: full two-call commit/effects, delayed/failed commit, result failure stops next effect, four finish-reason failures, empty/bad arguments, Plan/revocation, precise control absent from USER history, pre-start/during-model cancellation, JSON/CAS/scope/list/delete, four force-halt73 JVM windows, actual JVM approval exit/restore, modified/duplicate approval, invalid bundle/duplicate authority. Original call IDs call-1/call-2 persist; normal/prepared/success recovery effects=2 and modelCalls=2; unknown-effect window stays effects=1/modelCalls=1 and restoration fails.
- ProviderAndStateProbeTest: actual OpenAI2.0.4 extension against localhost SSE (one request, hello, finishReason stop, usage3/2), concurrent CAS one winner, two spaces with same SDK user/session isolated, withdrawal between permission/execution zero effect, interrupt cancels pending model, unknown block/bad tool JSON reject, multikey manifest/corrupt/foreign state and forged caller-history reject.
- SdkSurfaceProbeTest: actual prepareCall (no request until subscribe), streamEvents, Plan enter/exit, middleware onion and error propagation. Framework maxRetries=1 means one physical attempt; zero is rejected by this SDK.
- HarnessComponentContractTest: real LoopDriver with three substituted fake components, cancelled recall/retrieval futures, missing/repair evidence never success or second loop, references cannot carry tool facts or become SYSTEM authority, cancellation does not cross spaces. ArchitectureTest enforces the module DAG and pure Java domain/api/types; HealthHttpTest verifies real HTTP/Jackson3 shell and sanitized Problem.
- SDK control transport correction: real PreCall rejects SYSTEM input. Metadata-only TOOL control is consumed by confirmation extraction and never enters history/model; no fabricated USER approval text. Reconstruction preserves SDK assistant name platform-runtime, excludes model SYSTEM projections from history and reuses committed operation results. JSON2 state codec is a private instance; HTTP remains JSON3.
- Frontend Node22.22.2/pnpm10.34.7 contracts:check/typecheck/test/build passed, 15 unit cases. No wire contract or UI capability added.

## Linux, shell and resolved versions

- Profile command: `docker compose -p agentplatform-app -f deploy/compose.r0.1.yaml --profile framework-probe run --name agentplatform-r02-probe framework-probe`, image r0.2-local. JUnit Console completed36/36, zero skip/failure, exit0; report copied from /reports to evidence/r0.2/linux-reports. Log evidence/r0.2/linux-probes.log.
- Inspected probe: user10001, readOnly=true, network_mode=none, privileged=false; no host workspace/socket, dropped ALL capabilities, CPU2/memory1GiB/PIDs128. Local image sha256:6e5d889b046241f26b967f4a9170943f33c99d54758ad0a578fc8ae0e47df39a; JDK base pinned sha256:0bfc69a4758a86710e5c474032d28400a8bd00874766f9e8b1642ac2fd293159. This is not a runner/XFS or production release assertion.
- Existing shell rebuilt and healthy, SDK flag false. API image sha256:e23b0a825da0b7f6cd12e2e12580671794d666a55dd6ac740c20d85b954f5b30; gateway sha256:75050300381948977dcbf68dc214c0bb95214630fc437cf0cab43c229d1d1ec8. Jar SHA256 c0922a1e30df0d0a7d6e8779f2cc55acf8e5266bdd83bab72fcfac3b86ab4a26.
- Real API `API_BASE_URL=http://127.0.0.1:18080 pnpm contracts:test` passed; `pnpm test:e2e`3/3 passed31.6s, including actual API stop/up. Docker desktop/mobile/offline/API-stopped screenshots remain in ignored frontend/test-results and CI artifacts. Frontend assets/wire schemas unchanged.
- Resolved dependency tree (ignored platform-app/target/dependency-tree.txt): agentscope-harness/core/openai2.0.4; Reactor3.8.4 selected by existing Boot BOM; Jackson2.21.1 framework and Jackson3.1.0 HTTP; Jupiter/Console6.0.3. No SDK/Boot version or Enforcer bypass; full graph converged and JSON/state/HTTP tests passed.
- Initial failed Linux architecture case: flattened test directory was not recognized by ArchUnit's test exclusion and old relative `..api..` also matched org.junit.jupiter.api. Preserved target/test-classes in the image and scoped module rules to com.agentplatform.*; host architecture suite and Linux36 cases reran PASS. Failed reports retained at evidence/r0.2/failed-linux-architecture; no assertion weakened.

## Actual fixed-version signatures

- HarnessAgent.prepareRun(List<Msg>,RuntimeContext): AgentRun<AgentEvent>; prepareCall(...): AgentRun<Msg>; streamEvents(...): Flux<AgentEvent>; enterPlanMode/exitPlanMode(RuntimeContext); AgentRun.cancel/interrupt/stream.
- MiddlewareBase.onReasoning(Agent,RuntimeContext,ReasoningInput,Function<ReasoningInput,Flux<AgentEvent>>); onActing(...,ActingInput,...); onModelCall(...,ModelCallInput,...). Reasoning waits full stream; ActingInput contains complete List<ToolUseBlock>. order larger wraps outermost.
- ToolBase.checkPermissions(Map<String,Object>,PermissionContextState): Mono<PermissionDecision>; callAsync(ToolCallParam): Mono<ToolResultBlock>. Registered SDK tool surface is narrowed to controlled catalog before execution; policy is rechecked at actual call entry.
- AgentStateStore supportsVersioning/getVersioned/saveIfVersion; absent version0; SDK UNVERSIONED=-1 conflict converted into explicit adapter error. Model SYSTEM projections are not stored history. Private state is scope-validated and bundle/manifest/schema verified before reconstruction.
- OpenAIChatModel.builder/apiKey/modelName/baseUrl/stream are compiled and exercised only against localhost fixture, not a real supplier. Fake models/tools, real SDK, real JVM exits and Linux execution are distinguished from real supplier/production storage evidence.
