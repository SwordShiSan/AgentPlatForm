# Design

## Approved plan and dependencies
Implements the Plan-mode R0.1 plan approved by the user's implementation request on 2026-10-13. Canonical design is outside this repository; this document records implementation decisions rather than duplicating it.

## Engineering boundaries
Java 21, Boot 4.0.4 and Maven Wrapper 3.9.11. Six modules under com.agentplatform: types; domain -> types; api -> types; infrastructure -> domain/types; trigger -> api/domain/types; app -> all other modules. Use-case interfaces live in api, implementation in app, HTTP in trigger. ArchUnit checks the DAG and forbids domain dependencies on Spring, SDKs, Reactor and persistence. Maven Enforcer checks Java/Maven versions and convergence. Flyway is managed and a migration directory provided, but no database is required by the health shell or schema created until R0.3.

Vue 3.5 / Vite 7 / TypeScript 5.9 / Router 4 / Pinia 3 / Vue Query / Naive UI 2. Pin exact resolved versions; no latest tags. Query owns health resource state; Pinia owns UI selection. No placeholder authentication or agent APIs.

## Interfaces and data flow
contracts/openapi.yaml is OpenAPI 3.1. GET /api/v1/health -> {status: "UP"}; errors use application/problem+json and stable code/traceId/retryable fields. The endpoint confirms only the R0.1 application shell, not model/storage readiness. Event JSON Schema defines the version-1 envelope and registered event kinds without publishing fictitious events.

Schema validation -> TypeScript types/client generation -> request adapter -> Vue Query -> health page. Generated files carry a generated marker. The adapter sends same-origin cookie, optional CSRF/space/If-Match and caller-supplied idempotency key, never generates replacement keys. GET retries only network failures up to twice; writes have no automatic retries. Problem messages contain no stack traces; 401 is surfaced as a session-expiry event for later identity routing.

## Acceptance and recovery
Build fixed Java and frontend artifacts, package non-root containers, and use an isolated agentplatform-app Compose project binding gateway to loopback. Gateway connects to a dedicated ingress network and the internal backplane; API connects only to the backplane and has no published port. No Docker socket/privileges/legacy volumes. restart=no. Health request goes gateway -> API. API downtime displays unavailable, never UP; restart restores page after refresh/retry.

CI runs Java verify, contract schema/generation drift checks, frontend typecheck/unit/build, OpenSpec strict validation, links and Docker-backed real-API Playwright. Failed checks cannot promote. Main protection is a required hosting gate; the private repository currently reports a plan limitation, so local implementation can proceed but VERIFIED/main promotion stays blocked until actual protection exists.

## Compatibility and rollback
Only additive new shell contracts. No legacy migration or SDK integration. Rollback stops only this named Compose project without deleting volumes; retain the step branch and evidence. Exact changes are reviewed before step -> phase and phase -> main PRs. No cloud deployment at R0.1. Subsequent R0 steps require their own Plan and change.
