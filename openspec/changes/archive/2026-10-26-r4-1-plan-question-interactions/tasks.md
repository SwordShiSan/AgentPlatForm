## 1. Normative gate
- [x] 1.1 Independently promote local-sandbox documentation correction; synchronize main and create R4 branches.
- [x] 1.2 Record approved plan, ADR, contracts and five OpenSpec artifacts; synchronize stage boundaries.
- [x] 1.3 Pass strict before any implementation changes (2026-10-26; valid=true, no issues).
## 2. SDK feasibility and typed controls
- [x] 2.1 Add typed CONTROL definitions and nested validation with legacy probe compatibility.
- [x] 2.2 Verify real SDK structured tool → ASK → confirmed termination → JVM exit → new-attempt exact confirmation; preserve old fixtures.
- [x] 2.3 Pass production tool schemas and streamed calls through the existing OpenAI provider; one CONTROL per round.
## 3. Persistence and execution
- [x] 3.1 Add V15 plan/revision/interaction/answer/resume records with space, ownership, uniqueness and CAS constraints.
- [x] 3.2 Implement MyBatis repositories and short transactional control/answer services, outbox handoff and waiting conversation guard.
- [x] 3.3 Seal WAITING_INPUT checkpoint and actual stop receipt; restore immutable state plus answer watermark in a new attempt.
- [x] 3.4 Implement immutable edits, plan-only completion, required/optional questions, expiry, cancel and authorization races.
- [x] 3.5 Remove business4call/8round/recovery-count ceilings; implement configurable daily per-user1Mtoken quota, administrator unlimited/metered, atomic per-call reservations and cumulative active120seconds; default cost is metered only.
## 4. Contract and frontend
- [x] 4.1 Update OpenAPI and strict event payloads, generate clients and reject illegal fixtures.
- [x] 4.2 Implement Plan toggle/cards, questions, revisions, waiting/resume UI, conflict and lost-response handling.
## 5. Verification and closure
- [x] 5.1 Run engineering/architecture/persistence/Mapper/XML/frontend/contracts/links/strict and compatibility tests.
- [x] 5.2 Run isolated real PG/MQ concurrency, fault windows, integrity, authorization and budget-conservation cases.
- [x] 5.3 Run browser flows/accessibility/layouts and minimal Flash real acceptance under protected20request counter.
- [x] 5.4 Bind exact source/image/migration/evidence and complete protected step→phase→main PRs, archive only this change.
- [x] 5.5 Update ledgers, ADR, Skill and protected body/evidence delivery; stop local acceptance resources; production stays R3.
