# R0 HTTP production release
## Why
R0.1–R0.4 are VERIFIED. The user approved the release plan and explicitly replaced R0 DNS/TLS gates with HTTP-only service/IP/Host routing acceptance on2026-10-15.
## What Changes
- Publish the existing immutable gateway on80 for agentplatform.lailihao.cloud and124.222.55.140; no DNS edits, certificates,443 or redirects.
- Add guarded production lifecycle with bounded container retries and mount/capacity checks; retain rollback to original legacy containers.
- Complete fresh R0 regression,serial recovery and>=30min production observation; only then create production tag and mark R0 RELEASED.
- Update canonical documentation and existing platform skill to the authorized HTTP and local/cloud-only workflow.
## Impact
No new business endpoints,schema changes or vendor source rebuilds. Existing9 images stay immutable. Old stack remains stopped after success,with data and rollback preserved. R1 does not start.
