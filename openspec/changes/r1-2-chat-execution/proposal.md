# R1.2 queued DeepSeek chat
## Why
R1.1 is VERIFIED. Deliver ordinary durable text chat before configuration UI and full R2 recovery, using real RabbitMQ handoff rather than controller execution.
## What Changes
- Additive V3 tenant-scoped conversation/message/task, idempotency, dispatch/outbox/inbox, calls, encrypted credentials and budget facts.
- One leased/fenced worker slot, publisher confirms, persisted streaming/SSE, cancellation and bounded calls.
- AgentScope 2.0.4 streaming DeepSeek Flash adapter; local-only UI/fixtures with adjustable per-service memory within host total.
## Capabilities
### New Capabilities
- `queued-text-chat`: Transactional queued chat, streaming, budgets and isolation.
### Modified Capabilities
- `framework-runtime`: Backward-compatible streaming/cancellation ports and bounded SDK event consumption.
## Impact
Six DDD modules, contracts and Vue. No V1/V2 changes, cloud deployment, hosted CI, tools, shell or third-party source rebuilds. R1.3/full R2 recovery remain separate.
