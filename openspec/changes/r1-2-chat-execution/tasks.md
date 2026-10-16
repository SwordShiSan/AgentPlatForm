## 1. Gate and contracts
- [x] 1.1 Record approved plan/ADR/memory decision and validate strict before code.
- [x] 1.2 Add V3/OpenAPI/event payloads/generated client and compatibility locks.
## 2. Durable execution
- [x] 2.1 Implement tenant repository, idempotency, budgets, encrypted credential import and audit.
- [x] 2.2 Implement RabbitMQ outbox confirms, durable claim/inbox/fence/single slot and recovery boundaries.
- [x] 2.3 Add live HC-1/AgentScope DeepSeek streaming, bounded calls/usage, cancellation and context.
- [x] 2.4 Expose authenticated chat APIs, snapshot and committed SSE.
## 3. UI and fixture
- [x] 3.1 Implement ordinary chat UI with cursor reducer, states, history, cancellation and cache isolation.
- [x] 3.2 Build isolated opt-in local profile with redistributable measured memory and safe secrets.
## 4. Acceptance
- [ ] 4.1 Run full engineering/R1.1/old SDK/state/Jackson/contract/strict regression.
- [ ] 4.2 Run real MQ/PG atomicity/duplicate/confirm/ACK/fence/outage/order/budget cases.
- [ ] 4.3 Run deterministic model failures/stream/cancel/context and real DeepSeek within caps.
- [ ] 4.4 Run multiuser/browser/resources/leak checks, bind exact source/image/evidence.
## 5. Delivery
- [ ] 5.1 Review and protected step→phase→main PR promotion without hosted CI.
- [ ] 5.2 Archive/sync/ledgers/handoff; R1.2 VERIFIED only on all mandatory PASS.
