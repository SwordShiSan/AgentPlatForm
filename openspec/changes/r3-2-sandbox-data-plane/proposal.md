# R3.2 sandbox preparation and private data plane

## Why
R3.1 stores directory/quota facts but environment is NOT_CREATED. This change connects durable environment ownership to a real constrained gVisor container and the same workspace through verified file/exec ports.

## What Changes
- Add explicit guest RLIMIT_NPROC32 alongside unchanged host pids.max128, bind it into policy hash and fail-closed inspect. This follows a passing no-data official-runtime compatibility pilot; full candidate acceptance remains required.
- Add V11 environment, operation/dispatch/command/receipt, single sandbox slot, data-use lease and stop facts; retain V1–V10 and runtime baseline1.
- Extend signed runner with a separate versioned environment protocol while retaining PROVISION v1.
- Prepare/query/definite retry API and environment status UI; private fixed probes only for data-plane execution.
- Implement strict mount/runtime policy, stopped-owner proof, idle60 release and unknown-result retention.
- No public shell/file APIs, model tool registration, supplier calls or R3 production deployment.

## Impact
Six existing modules, generated HTTP/event clients, scoped MyBatis grants, independent local/Linus acceptance and protected step→phase→main. Production R2 is restored after any approved serial maintenance window. Historical R3.1 evidence remains separate.

## Approved file-helper correction
User selected the trusted file-helper route on2026-10-23 after actual runsc path-race failure and same-host controls. Add V12 scoped helper intent/identity/immutable stop facts without modifying V1–V11; keep untrusted execution on gVisor. Fixed non-root64MiB helper has no network/capabilities/socket/credentials, shares the auxiliary slot with quota verification, and cannot run user programs. Preserve one use result authority, query-only recovery and helper+sandbox stop barriers. No host-level diagnostic workaround or third-party source modification.
