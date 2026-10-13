## Context

R0–R9 are major phases, each containing four independently verifiable small phases. Local Docker acceptance establishes each small phase as stable for continued development. Cloud deployment is reserved for the full major-phase snapshot and must not be conflated with when the development main branch advances.

## Decisions

1. **Separate code stability from production release.** `main` is the latest locally Docker-verified small-phase commit. An immutable `production-rN-<sha>` tag and image digest identify the last major phase that passed cloud acceptance. Main may lead production during an active phase.
2. **Retain both branch levels.** Create `phase/rN-topic` from the current main at the start of a major phase. Create one `step/rN.n-topic` branch from the current phase for each small phase. Never combine unfinished small phases on one step branch.
3. **Promote after every accepted small phase.** Run local frontend/backend/dependency/UI/regression gates and review. PR step→phase, run affected integration tests, then PR phase→main. After main advances, merge/synchronize main back into phase and record both PRs and SHAs before beginning the next step.
4. **Release only at the major-phase boundary.** Once all four steps are VERIFIED and phase is synchronized with main, run full local regression and build one immutable image. Canary that exact digest in local Docker, then deploy the same digest to cloud. Cloud tests and the observation window create the production tag and mark the major phase RELEASED; they do not perform an extra main merge.
5. **Keep incomplete behavior safe.** Any capability in main that is not yet part of the deployed production release remains disabled by default behind feature flags and cannot be exposed to production users prematurely.
6. **Handle cloud failure without invalidating local stability.** Roll production back to the previous production tag/digest. Keep main at the latest locally accepted small phase. Fix in a new step branch, pass local tests and PR promotion, then rerun the full major-phase release gate. Do not start the next major phase until the current one is RELEASED.

## Risks and Mitigations

- Main can lead production: tag every production deployment by source SHA and digest; report both identifiers separately; keep incomplete feature flags off.
- Phase and main can diverge after a PR merge: synchronize main back into phase after every promotion and test the updated phase before creating the next step.
- Local success may miss cloud-only behavior: full major-phase cloud tests remain mandatory and independently gate RELEASED/next phase.
- A production rollback cannot reverse external side effects or destructive schema changes: use additive migrations, compatibility windows, and the existing operation reconciliation/compensation procedures.

## Validation

Static consistency checks must confirm every relevant runbook, ledger, repository instruction, and skill uses the same branch flow; each local link resolves; no statement delays main promotion until cloud release. No runtime test is required because this change alters governance documentation only.
