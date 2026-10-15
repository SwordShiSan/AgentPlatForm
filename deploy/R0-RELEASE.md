# R0 HTTP production release2026-10-15

Status:**R0 RELEASED**;four small stages VERIFIED,R1 NOT_STARTED.

- HTTP entry:[public IP](http://124.222.55.140);Host `agentplatform.lailihao.cloud` configured and tested. DNS/TLS deliberately outside this approved release;no443,certificate or redirects.
- Immutable production tag:`production-r0-c46a73ae4d00` at `c46a73ae4d00ae4b8d5497e5bcf39114f2b044a8`. Configuration promotions:[PR18](https://github.com/SwordShiSan/AgentPlatForm/pull/18),[PR19](https://github.com/SwordShiSan/AgentPlatForm/pull/19).
- Reused9 exact linux/amd64 images from runtime source`c51fa352953d6155ef255df59c6cf486ec9a5df8`;image archiveSHA256`78dd032d3a3b9142e8f99754db5d66dbbdc02fb726cfbd89128da3502d110555`. Reviewed deployment inputs and image config IDs separately bound to the release source. No cloud builds/vendor source repairs.

## Actual acceptance
Maven44,Linux SDK39,real Java PG3,Vue15/local browser4/public browser4:PASS. PG/vector/Redis/MQ actual ACK loss/duplicate/fence,S3 hash/Range/multipart/restart/STS refusal:PASS. Official gVisor9/XFS quotas,mount guards,cold consistent backup and independent serial restore:PASS. Bad config hash,wrong UUID and missing mount deny startup without underlay writes;production service start/restart and12 profiles/permissions/retries/exposure:PASS. Redis/S3 outages each produce readiness503 and recover200 before observation.

New uninterrupted production observation:1810.89seconds/55iterations PASS;sampled minimum MemAvailable1920.8MiB,swap178.7→185.6MiB. Sample extrema are not absolute peaks or higher concurrency promises. Minimum4CPU/3655MiB/40GiB,single slot/sandbox,4GiB XFS,root reserve>=5GiB remain. Original XFS UUID unchanged. Business models,identity,scheduling and shell stay closed.

## Operation and evidence
New stack stays online;legacy10 containers retained stopped withrestart:no,original policies/data/current+rollback images preserved. Guarded systemd boot service enabled;normal retrieson-failure:3,migration/sandbox no automatic replay. Rollback:stop/disable new lifecycle,stop new stacks,then legacy-window restore and actual legacy-health checks;do not start both stacks.

Evidence package:`r0-http-release-evidence-20261015.tgz`,bytes1218407,SHA256`0507faa7499cb62f732fccaeba3f5b161ff16cc3a0eead2b7d575ae95342c44f`;protected local`evidence/r0-release`and cloud`/srv/agentplatform/operations/r0-http-release-c46a73ae4d00-20261015T123842Z` copies match. Retain>=30days. Exact command/assertion/file hashes,SBOM/dependency records and raw historical vendor findings are included/referenced;findings are not claimed fixed. Credentials,raw SQL/definitions and original backups are excluded from public Git.

Canonical body remains outside Git at`D:/java/Projects/项目文档/06-AgentScope重构专项`;existing platform skill updated at`C:/Users/laili/.codex/skills/ai-agent-platform`,with applied file hashes separately retained. Git push does not deliver these files;protected handoff archives are separately recorded. Archive/record-only PRs do not retag or rebuild the deployed candidate.
