# Current local sandbox decision (2026-10-26)
The user deferred cloud sandbox migration because of cost. Continue the released local Docker + gVisor/runsc sandbox, runner/docker-java, shared XFS workspace, fixed trusted storage helpers and SeaweedFS immutable versions. Existing ownership/fence/authorization and precise stop receipts remain authoritative; local process/transport cancellation or lease expiry alone is not stopping proof.

Keep the canonical R3.C plan, reviews, ADR-21 through ADR-23 and all files in `openspec/changes/r3-cloud-sandbox-alignment` as DEFERRED reference material, not current implementation requirements or an R4 prerequisite. The CLI may still enumerate this retained directory; that does not authorize applying it. Do not archive/merge cloud deltas into main specs, mark unimplemented work verified, add cloud SDK dependencies or implement the proposed V15. Its old migration number is not reserved for future work.

Retain the verified 2880MiB full local profile, including sandbox512MiB/runtime128MiB/helper64MiB. The cloud-only 2784MiB reallocation does not apply. Module limits may be adjusted from measurements within2887MiB total, with768MiB host reserve and no swap expansion; do not spend the local sandbox reserve twice. R3 remains RELEASED; R4 requires its own small-stage Plan/OpenSpec and has not started. This correction changes documentation only, not application, schema, deployment or cloud instances.

# Current user-approved R0.4 workflow exception (2026-10-15)
GitHub is version management only: Actions disabled, required CI retired, local/cloud exact-source acceptance evidence supports step→phase→main PRs. Preserve PR/non-force protection. No blanket third-party vulnerability remediation or source rebuild requirement; functionality/isolation/quota/resource/recovery gates remain. Same accepted Plan/OpenSpec applies; previous CI instructions below are historical where conflicting.

R1.4 approved decision (2026-10-17): current and subsequent deployments use HTTP; HTTPS is reserved/unimplemented, no DNS changes/checks. Explicit cloud HTTP identity profile uses non-Secure HttpOnly SameSite=Lax cookies, exact Origins and CSRF. Preserve functional authorization, credential encryption and redaction. Real acceptance is metered without fixed20call/1CNY ceilings; minimize calls and stop at provider quota/balance errors. Do not reset old UNKNOWN holds or disable business budgets.

# AgentPlatForm implementation rules

## Scope
R0.3 approved exception (2026-10-13): use the existing cloud Linux host for developer Docker acceptance in place of mandatory workstation Docker acceptance. The user authorized precisely inventoried obsolete image/log cleanup and reversible legacy container pause/restore. Preserve all legacy business data, referenced volumes, current and latest rollback images. No blanket prune, data migration or public-domain change. Production release gates remain independent.

Minimum-host sandbox policy: one execution slot and one on-demand gVisor runsc/systrap sandbox; no runc fallback. Use a preallocated dedicated XFS image filesystem with verified UUID/prjquota mount; missing mount must deny I/O. Full observability is an optional profile. Resource numbers and gates live in the canonical R0.3 plan; budgets are not capacity evidence.

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

## Framework alignment and implementation readiness (2026-10-18)
The `r2-framework-alignment` correction is VERIFIED (implementation PR50/51); preserve these framework boundaries in R2.2 and later. Read the canonical corrective plan and remaining-stage architecture guide linked in README. Every future Plan must name framework APIs, module/adapter/Mapper/XML/UI responsibilities, transaction and data flow, failure/recovery assertions and legacy capability mapping. Feature lists alone are insufficient. Prefer MyBatis/Spring transactions, Spring AMQP and supported AgentScope extensions; preserve platform ownership, budgets and authorization. No new business JDBC helper framework, hidden second scheduler/loop, or third-party source fork without an explicit reviewed design. The correction is complete; future capability gates remain independent and require their own Plan/OpenSpec.

Every persistence change must also pass `python deploy/scripts/check-persistence-boundaries.py`. Its exact driver/probe/TypeHandler exceptions are reviewed scope; do not broaden them to permit business JDBC in another package. ArchUnit enforces repository and DDD dependency boundaries in Maven.

Before promotion, run `git diff <stage-base> HEAD --check` as well as working-copy and staged checks. A clean working tree does not prove the committed stage patch is whitespace-clean.
