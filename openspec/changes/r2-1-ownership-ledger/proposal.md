## Why

R1 queues chat durably, but SDK state mutations are not fenced and a successful physical call is settled before the complete logical round is committed. R2.1 establishes ownership and immutable recovery facts before further recovery features.

## What Changes

- Add V6 conversation ownership, model rounds, tool facts, checkpoint snapshots and stopped-owner proofs.
- Fence every execution mutation, including SDK CAS; retain one global slot and ACK only after durable handoff.
- Commit complete rounds, call lists and successful-call accounting together; preserve UNKNOWN reservations.
- Seal immutable checkpoints and validate bundle/state hashes without empty recovery fallback.
- Require trusted host stop evidence; requeue only definitely unstarted work, finalize committed terminal rounds, block unknown requests.
- Local two-worker acceptance only, no cloud update and no real provider calls.

## Capabilities

### New Capabilities
- `execution-ledger`: fenced durable ownership, canonical rounds and recoverable checkpoint evidence.

### Modified Capabilities
None. Existing public HTTP shapes remain unchanged.

## Impact

Domain ports, app coordination, PostgreSQL/AgentScope adapters, operator scripts and isolated local Compose. Java21/AgentScope2.0.4 and V1–V5 remain unchanged. R1 production stays online; real tools, full checkpoint continuation UI and provider restart demonstration remain later gates.
