# AgentPlatForm implementation rules

## Scope
This is an independent monorepo. Keep frontend and backend code in their respective directories. Do not change legacy repositories, databases, containers, or deployments while implementing this platform.

Read the reconstruction navigation and the current R-stage execution document linked in README before implementing. The canonical design body is outside this repo; do not create a duplicate body here.

## Architecture
Frontend: Vue 3 + TypeScript Composition API, Router, Pinia for UI state, Vue Query for server state.
Backend: Java 21, six DDD modules; domain must not import AgentScope, Spring AI, controllers, or database adapters. AgentScope 2.0.4 belongs in infrastructure; no Spring AI in the execution path.
Platform task queue owns scheduling. Browser disconnect is not cancellation. All tools share the runner workspace and the same authorization/operation ledger.
Harness internals use explicit versioned ports and build-time composition, not runtime plugins. Keep one ReAct loop driver and one compaction owner per run. Context assembly, long-term memory, retrieval, tool execution and completion verification have separate responsibilities. Pin component bundles for active tasks; do not hot-swap them through Skills. Read the HC-1 contract and EX07–EX10 design documents before changing these boundaries.

## Workflow and evidence
Execution status lives only in the canonical reconstruction folder `08-重构进度管控/阶段/`. Read its global rollup and the current local ledger first; update the local step with commit, commands, case results and UI evidence before updating the rollup. The `07-交付与验收/阶段/` files define plans, not duplicate live status. Documentation completion never means R0 is implemented.

For each major phase, create a `phase/rN-topic` integration branch; create a separate `step/rN.n-topic` branch for every small phase. A step starts from the current phase branch. After its local Docker/UI/backend/dependency gates pass, review and PR step→phase, then PR the updated phase snapshot→main so **every accepted small phase advances main**. Record both PRs/SHAs and sync main back into phase before starting the next step. `main` is the latest locally verified stable code and may be ahead of production; keep unfinished capabilities behind disabled feature flags.

At the end of each major phase, run full local regression, build an immutable image, canary that same digest in local Docker, then deploy the digest to cloud and run online tests/observation. Only successful cloud validation creates the immutable production tag and marks the major phase RELEASED; it does not determine when main advances. On cloud failure, roll back the production deployment only; keep main at its latest locally verified small phase and fix through a new step branch, local gates, and PR before rerunning the cloud release gate. Do not start the next major phase until the previous one is RELEASED.

Each small stage updates contracts/data, implements UI and backend, tests, and documents evidence before the next stage. Do not report compile-only, mocked success, or skipped real-environment tests as verified behavior.
Use OpenSpec proposal/specs/design/tasks/verification for implementation changes; documentation status is not feature completion.
Do not run blanket git add. Stage explicit scoped files. Never commit credentials, logs, runtime data, node_modules or generated build output.

## Build
R0.1 establishes the Maven Wrapper 3.9.11, pnpm 10.34.7 lockfile and CI. Use backend/mvnw (mvnw.cmd on Windows) and frontend package scripts with the committed pnpm lockfile. Node is pinned by .node-version. Follow each stage's actual acceptance status; having manifests does not mean R0.1 is VERIFIED.
Java uses four spaces; TypeScript two spaces. UTF-8/LF. Generate API clients from contracts; never hand-maintain divergent DTOs.

## Safety
No default full-power guest account. FULL tool permission does not bypass space ACLs, quotas or mandatory policies. Do not blindly replay unknown side effects. Do not deploy, create a remote repository, or delete legacy files without explicit scope and authorization.
