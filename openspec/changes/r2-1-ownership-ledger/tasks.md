## 1. Specification gate
- [x] 1.1 Approve Plan and inspect current implementation/baseline.
- [x] 1.2 Synchronize formal plan, ADR, HC-1 and phase docs; pass strict before code (2026-10-18 exit0).
## 2. Ownership and persistence
- [x] 2.1 Add V6 ownership, logical rounds, operations, checkpoint snapshots and stop evidence.
- [x] 2.2 Fence all execution/SDK writes and durable two-worker claim/ACK path.
- [x] 2.3 Commit successful physical accounting with canonical round and operation list.
- [x] 2.4 Seal/validate immutable versioned checkpoints and guarded state namespaces.
- [x] 2.5 Implement trusted stopped-owner reconciliation without bare flag bypass.
## 3. Acceptance
- [x] 3.1 Engineering, contract, old-state and Jackson isolation regressions.
- [x] 3.2 Fresh/upgrade real PG, transactional rollback and live ACL/fence tests.
- [x] 3.3 Real MQ and two-worker crash/ACK/confirm/safe-requeue/UNKNOWN tests.
- [x] 3.4 Checkpoint corruption, tool fault windows and proof rejection tests.
- [x] 3.5 Existing-chat browser and bounded resource tests, zero real calls.
## 4. Promotion and handoff
- [x] 4.1 Review exact candidate, bind source/images/commands and immutable evidence hashes.
- [ ] 4.2 Complete step→phase→main PRs, synchronize phase and archive change.
- [ ] 4.3 Update local ledger then rollup, package protected evidence/body and record delivery.
