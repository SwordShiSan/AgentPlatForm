# R0.1 engineering and contract toolchain

## Why
The independent repository contains navigation only and cannot build or run. R0 needs a reproducible, locally verified foundation before any AgentScope execution experiments.

## What Changes
- Establish Java 21 / Boot 4.0.4 six-module Maven and Vue/TypeScript applications with pinned tools, wrappers and lockfiles.
- Add the real health API, Problem contract, event-envelope schema and deterministic generated TypeScript client/types.
- Add architecture, request-adapter and real-API UI checks, Docker acceptance and CI.
- Require two-PR promotion and main protection; keep unmet environment/hosting gates explicitly blocked.

## Impact
Only AgentPlatForm and its canonical external progress documents change. No legacy service, data, credentials or production entrypoint is touched. AgentScope, task execution, identity, business migrations and full middleware belong to subsequent stages.
