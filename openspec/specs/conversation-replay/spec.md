# conversation-replay Specification

## Purpose
TBD - created by archiving change r2-2-snapshot-event-stream. Update Purpose after archive.
## Requirements
### Requirement: Consistent authorized snapshots and replay
The platform SHALL read snapshots and replay pages in short consistent authorized transactions and publish only committed projection events.
#### Scenario: Commit or rollback races a snapshot
- **WHEN** a writer changes text and events during a read
- **THEN** projection and cursor describe one committed view and rollback produces no visible event
#### Scenario: Cursor recovery and boundaries
- **WHEN** after and Last-Event-ID differ or a cursor is invalid/expired
- **THEN** after wins and malformed400, ahead/negative409, pruned410 are enforced before200; fresh snapshot resumes without creating a task

### Requirement: Bounded framework SSE lifecycle
The platform SHALL use Spring MVC managed reactive SSE, bounded admission/buffers/executors and explicit deadlines without enabling WebFlux or coupling disconnect to cancellation.
#### Scenario: Slow or excess clients
- **WHEN** eight streams are active or a client stops reading
- **THEN** the ninth receives503, buffers remain within256events/1MiB, resources release within35seconds and healthy tasks/readiness remain functional
#### Scenario: Revocation or dependency loss
- **WHEN** identity is revoked or DB becomes unavailable
- **THEN** the stream closes without private details, timers/permits are released and reconnection reauthorizes without model replay

### Requirement: Monotonic safe retention
The platform SHALL retain replay events for at least30days and atomically prune only an expired continuous prefix with a monotonic persisted watermark.
#### Scenario: Cleanup races append and readers
- **WHEN** collectors compete while writes and reads continue
- **THEN** locks serialize prefix changes, no young event or canonical message/ledger/outbox/inbox is deleted and existing snapshots remain usable
#### Scenario: Collector fails or history is empty
- **WHEN** deletion fails, all eligible rows disappear, or an upgraded conversation has missing prefix
- **THEN** rollback preserves rows/watermark; valid snapshot cursor still resumes and expired cursor gets410

### Requirement: Generation-scoped browser recovery
The browser SHALL parse and validate SSE through pinned frameworks, advance only applied cursors and keep one connection per current actor/space/conversation generation.
#### Scenario: Duplicate gap unicode or old generation
- **WHEN** events repeat, arrive late, contain Unicode fragments or have a gap
- **THEN** no duplicate text/task or cross-context update occurs, and rebase uses a fresh authoritative snapshot
#### Scenario: Offline authentication and capacity failures
- **WHEN** a stream fails or the page is switched/unmounted
- **THEN** abort/timers are cleaned, network503 backs off,401 clears identity,403/404 stop, and unknown calls are never resubmitted
