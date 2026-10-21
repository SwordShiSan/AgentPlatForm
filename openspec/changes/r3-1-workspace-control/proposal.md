# R3.1 workspace control and authenticated runner

## Why
R2 is released. Provide durable personal-space workspace provisioning, not an in-memory demo or user execution. The approved prerequisite switches the retained production runtime back from legacy to R2 before development.

## What Changes
- Additive V10 workspace/operation/command/receipt/lease/capacity authority, MyBatis repositories and Spring transactions.
- Transaction outbox -> RabbitMQ confirm -> durable claim/ACK -> authenticated internal HTTP runner. Explicit UNKNOWN reconciliation, no database execution fallback.
- POST/list/detail/retry workspace APIs and Vue creation/status. READY denotes storage only; environment NOT_CREATED and all execution/file capabilities remain disabled.
- Spring Security/Nimbus RS256 request and response signatures, docker-java3.7.1/core+HttpClient5, fixed privileged quota helper and single2GiB workspace capacity.
- Local gates and only necessary isolated Linux maintenance acceptance; restore unchanged R2 afterwards. No paid calls, R3 production deployment or R3 release tag.

## Impact
Six existing modules, contracts/generated clients, additive migration and deployment profiles. Freeze new dependencies and retain Java21/Boot4.0.8/AgentScope2.0.4. No V1-V9 edits or existing chat/task semantics change.
# Native loop-device feasibility clarification

Actual Linux evidence found that xfs_quota needs the mounted loop block-device node even for state queries: the mount/UUID were visible and host accounting/enforcement were ON, but the helper without that node returned an empty state and a native "No such device" diagnostic. Mapping **only the same verified `/dev/loopN` with device permission `r`** made the native state query succeed, without a new capability. Freeze this operator-owned loop reference into runner configuration, never public requests; a missing/mismatched reference fails closed. API/worker/sandbox receive no device and the existing capability allowlist stays unchanged. No privileged mode, writable raw device, wildcard cgroup device rule, third-party source change or production deployment is introduced. Same change, V10 unchanged; final candidate affected checks and observation must be rerun.
