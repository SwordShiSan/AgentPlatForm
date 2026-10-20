# R2.2 snapshot and event replay
## Why
R2.C is VERIFIED at9412d2c. Current replay uses an inline blocking loop, age filtering without physical collection, and EventSource errors always fetch snapshots. Complete reliable recovery and bounded framework lifecycle.
## What Changes
- Preserve public paths/schema1 and persist V7 replay prefix watermark with controlled30-day collection.
- Introduce snapshot/event query services, typed EventMapper, Spring MVC Reactor SSE with explicit deadlines and bounded admission.
- Fetch/eventsource-parser4.1.2 connection manager, Ajv standalone validator and generation-scoped Query reducer.
- Local deterministic acceptance only; cloud R1 unchanged, provider calls0, no new tools/recovery UI.
## Capabilities
### New Capabilities
- `conversation-replay`: consistent replay, bounded stream lifecycle and safe prefix retention.
### Modified Capabilities
None; existing queued-text-chat and ownership requirements remain binding.
## Impact
Six-module adapters/UI/contract clarification, V7 additive migration, dependency locks and isolatedr22 fixture. No V1-V6 or Harness codec change. Approved Plan before this change; strict required before implementation.
