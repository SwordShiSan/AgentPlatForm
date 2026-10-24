# R3.3 file assets and quota
## Why
R3.2 verified preparation and private data-plane probes. Users need real file browsing, uploads, explicit versioned replacement and immutable downloads without starting a sandbox. Small Base64 probes cannot serve100MiB files.
## What Changes
- Add durable file/upload/version/reservation/operation facts through additive V13; preserve V1–V12 and runtime baseline1.
- Add controlled streaming S3 and trusted-helper transport, confirmed stop and query-only reconciliation.
- Publish scoped directory, upload, metadata/content/Range and storage-usage APIs and Vue UI.
- Explicit replacement uses If-Match and baseVersion; text editing/move/delete/restore stay in R5.
## Capabilities
### New Capabilities
- `file-assets-quota`: bounded streaming, immutable versions, authoritative workspace effects and scoped accounting.
### Modified Capabilities
## Impact
Six-module DDD, contracts, MyBatis/XML/Spring transactions, Spring AMQP, AWS SDK2, runner/docker-java, Vue Query. No vendor calls, R3 production deployment or new AgentScope tool registration; R2 stays production.

Upload metadata recovery reads only the original object and requires complete integrity/current revision; it never retries content writes, automatically refunds or queues filesystem effects. Public SDK write uncertainty uses a stable non-retryable Problem code; internal diagnostics are fixed-category only.
# Runtime configuration clarification
Use official bounded SeaweedFS volume-growth settings so filer metadata does not exhaust all slots before asset data can be assigned. This is fixture/deployment configuration within the approved stack; no third-party rebuild, permission expansion or public-contract change.
