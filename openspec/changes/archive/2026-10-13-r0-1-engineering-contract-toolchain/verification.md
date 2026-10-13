# R0.1 verification

Status: VERIFIED after PR #2 and PR #3 promotion on 2026-10-13. Baseline: 38afe23edf49387635af64d198fa867413d76f61. Plan approved 2026-10-13. Local and integration CI passed; protected main and phase synchronized at 2e9406640ff5e8e5538bbbacccf85c9f92ff0398.

| Case | Spec requirement / scenario | Command or method | Expected | Result |
|---|---|---|---|---|
| R01-SPEC | Traceable acceptance / Incomplete acceptance | openspec validate r0-1-engineering-contract-toolchain --strict --json | valid change before code | PASS, exit 0; all-spec strict validation also passed |
| R01-JAVA | Reproducible engineering / Clean build | backend/mvnw.cmd -B -ntp verify | all six modules pass, Java21, architecture DAG | PASS, final 5 tests (3 architecture + 2 real HTTP), exit 0 |
| R01-CONTRACT | Single machine contract / Regeneration; Invalid event fixture | frontend pnpm contracts:generate then contracts:check; API_BASE_URL=http://127.0.0.1:18080 pnpm contracts:test | deterministic output; valid/invalid fixtures; real HTTP schema | PASS, exit 0 |
| R01-ADAPTER | Safe request boundary / Failure and write retry | frontend pnpm test | GET max 2 network retries; POST no retry; Problem/401 preserved | PASS, 15 cases, exit 0 |
| R01-FRONTEND | Reproducible engineering / Clean build | frontend pnpm install --frozen-lockfile; pnpm typecheck; pnpm build | pinned lockfile builds | PASS, exit 0 |
| R01-UI | Honest health shell / API unavailable and restart | Docker + pnpm test:e2e | UP via real API; outage visible; recovery; screenshot | PASS, 3 cases, 20.6s, exit 0 |
| R01-GOVERNANCE | Traceable acceptance / Incomplete acceptance | protected main + two PRs + ledgers | all actual gates before VERIFIED | PASS: main protected with mandatory PR/CI, both PRs merged and ledgers updated |

Record exact commands, exits, tool/fixture versions, source SHA and image identifiers after execution. CI logs/UI screenshots live in ignored evidence artifacts; link or summarize them without credentials. Real model and full middleware experiments are deferred to their designated R0 steps, not reported as passed.

## Local environment and evidence (2026-10-13)

- Windows host Java 21.0.10; Maven Wrapper 3.9.11; project-local Node 22.22.2 and pnpm 10.34.7; Docker 28.5.1 Linux/amd64; Playwright 1.64.0 Chromium v1248. Node 22.18 could not meet nopt's >=22.22.2 floor; fixed within the approved Node22 line, no global replacement.
- Boot BOM resolves JUnit Jupiter 6.0.3, Flyway 11.14.1, Testcontainers 2.0.4, HTTP Jackson 3.1.0 and Flyway Jackson 2.21.1; ArchUnit 1.4.1. Full tree: backend/platform-app/target/dependency-tree.txt; effective POM: backend/target/effective-pom.xml (ignored artifacts). No AgentScope runtime test claimed.
- pnpm audit --prod --json: zero reported advisories across 67 production dependencies (exit 0); not a backend/container CVE or SBOM claim.
- Canonical links: REQUIRE_CANONICAL_DOCS=1 pnpm links:check, 17 local and 13 external canonical references verified, exit 0. Git whitespace and scoped secret-pattern checks passed.
- OpenAPI SHA256: 3bd52378ffb922a24480ffb21e7ddcfbc23b59e6cad788700e14b416c797d64e; event schema SHA256: 7fa3fb9dd9120511f0e74e7425a419e7e161dbfd48deec25693c79e2e44002e5. Fixture variants are embedded in frontend/scripts/contracts.mjs.
- API local image digest: sha256:164b7d3e230deea409e550d0e70166dbd9ed20e84c6e4ce0a2809abb8991d2e5; gateway: sha256:7ecfe8e171b75769f4b9aad813ed35c1ee913071b4676b374067dea8c510d453. These are local image evidence, not published production images.
- Inspected containers: users 10001/101, readOnly=true, privileged=false, cap_drop=ALL, no bind mounts/Docker socket, no API host port, gateway loopback18080 only. R0.1 intentionally has no DB/runner/workspace and makes no XFS claim.
- Screenshots (ignored): frontend/test-results/health-desktop.png (1280x720/full page), health-mobile.png (375x812), health-offline.png, health-api-stopped.png. Desktop/mobile screenshots visually reviewed; normal/error/recovery scenarios passed against fixed dist and real Docker API.
- Initial failures: internal-only gateway networking suppressed host publishing; added dedicated gateway ingress while keeping API isolated. First browser recovery harness used unsupported compose start --wait; changed to up --no-build --wait and reran all three cases successfully. First dependency-tree download failed with Central TLS interruption; retry succeeded without changing dependency versions/cache artifacts.
- Historical hosting blocker: private repository lacked protection support. Owner changed repository to public; saved main protection requiring PR, R0.1 toolchain checks from GitHub Actions, up-to-date branches, no administrator bypass, no force pushes or deletion. Branch GET confirmed protected=true and enforcement_level=everyone. The blocker is resolved; no bypass was used.

## Git delivery

- Implementation commit: 4e1e8f461f9890566055b54adf530bd891bb1015; pushed and confirmed by ls-remote. Runtime artifact SHA256 equals the running container jar: c1d95c6b8332766cf00f644cfc3d1de8e0848108a54be95e63941d549a69c84e.
- Step -> phase PR #2: https://github.com/SwordShiSan/AgentPlatForm/pull/2; merge e380fc6a713373af882ee58ab19424cc03690438. Phase -> main PR #3: https://github.com/SwordShiSan/AgentPlatForm/pull/3; merge 2e9406640ff5e8e5538bbbacccf85c9f92ff0398.
- Integration CI https://github.com/SwordShiSan/AgentPlatForm/actions/runs/38358833798 completed success, including backend, frontend, contract, OpenSpec, Docker, real API/UI and cleanup. Step CI 38356522470/38356518546 also passed on ace418c. Phase tree matched tested step; main synchronized back into phase.
- Canonical progress/design files were updated locally outside this repository and are not included in repository commits. R0.1 VERIFIED does not mean R0 RELEASED; AgentScope, full middleware, XFS and production remain pending. The owner authorized autonomous PR review/merge after gates; GitHub's one-time identity recheck does not impose per-PR manual approval.
