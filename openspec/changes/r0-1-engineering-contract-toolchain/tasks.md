## 1. Preconditions and specification
- [x] 1.1 Verify remote baseline, create/push phase and step branches, pin runnable pnpm 10 and verify Linux Docker.
- [x] 1.2 Complete proposal/specs/design/tasks/verification and pass strict OpenSpec validation before implementation.
- [ ] 1.3 Configure and verify protected main with required PR/CI and no force/direct pushes; retain hosting limitation as blocked if unavailable.

## 2. Engineering and contracts
- [x] 2.1 Establish health/Problem OpenAPI and event schema; validate and generate deterministic frontend client/types.
- [x] 2.2 Establish six-module backend, Maven wrapper, dependency management, Enforcer, ArchUnit and Flyway migration preparation.
- [x] 2.3 Establish pinned Vue application, state ownership, health UI and request adapter with failure/retry tests.

## 3. Verification and delivery
- [x] 3.1 Pass backend verify, frontend typecheck/test/build, schemas, generation drift, OpenSpec and links.
- [x] 3.2 Build/start fixed Docker artifacts and pass real API/UI normal, failure and restart tests with screenshots.
- [x] 3.3 Add reproducible CI and run review/secret scan; record tool versions and resolved dependencies.
- [ ] 3.4 Review and promote through step -> phase and phase -> main PRs only after all gates; sync main back into phase.
- [ ] 3.5 Update canonical local ledger, rollup, risks and handoff with commands/results, PRs/SHAs and external-doc synchronization boundary; archive only after complete acceptance.
