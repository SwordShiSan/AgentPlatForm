# Verification

## Current foundation replan (2026-10-14)
Approved from5316ba6: Trixie/Noble/Alpine3.24 container bases and compatible source security rebuilds; host OS unchanged. Previous foundation scope question is resolved. New foundation builds/scans and full runtime reacceptance initially NOT_RUN; historical blocking reports retained. Test manifest/source/package drift rejection, exact applicability evidence invalidation, unrelated critical rejection, real compatibility and restore failure cleanup. No promotion until all mandatory gates pass.

Foundation implementation preparation: exact10 platform manifests, pgvector/SeaweedFS source commits and archive hashes, offline Alpine3.24 OpenSSL3.5.9/zlib1.3.2-r1/pcre2 packages and patched Go module files frozen. Python lock omission fixed: typing-extensions4.16.0 was required by actual CPython3.12 CI (prior run38511174960 failed storage-probe build). All12 wheels downloaded with hashes; complete install/pip-check remains CI-gated. Node22.23.3 and Temurin21.0.12.1 official archives hashed, HOST44/Vue15/type/build/frozen-lock PASS; foundation+MCP/workspace guards5 PASS. Contract eight-file freeze unchanged. New raw foundation audits FAIL; no broad VEX statements created. PG/vector/Redis/SeaweedFS Dockerfiles are candidates pending CI; actual vector distance/version check added. CI artifact Action upgraded to frozen v6 SHA; same protected check and30-day retention. No cloud services paused, no accepted export or promotion.

Actual binary audit adds blocking findings in gVisor20261005.0 and Syft/Grype products. Symbol-inclusive scans retained separately from original module-level scans. Frozen gVisor97e8e896 source candidate upgrades compiler to Go1.27.2, net0.60/crypto0.57/text0.42/mod0.41/grpc1.83.1/containerd2.1.9; compiler compatibility, networking unit tests and complete sidecar build remain NOT_RUN until CI passes. Candidate job exports analysis lock/hash records and is not an accepted runtime. No install or cloud daemon change is authorized by a candidate build result alone; full audit/runtime/recovery gates still required. If compiler compatibility fails, the approved Go1.26.9 fallback requires the same tests and fresh locks.

Run38537157463/current411cad5: missing compiler inputs corrected. Go1.27.2 gVisor test FAIL in upstream nogo importing internal/abi: export data version5 exceeds analyzer maximum4. Approved fallback uses SDK1.26.9 with unchanged static analysis/test targets and new module-file hash; no disabling nogo. Redis numeric999 group creation collision corrected, signed offline packages installed successfully in the previous failed attempt. Host/old Docker stack remains running (10containers); HTTP entry200; root21GiB free. New stack has not started. Candidate build/runtime/soak/recovery remain separate gates.

2026-10-14 independent security Plan completed and approved. Continue16677a8/same change. Earlier HOST scans are preparation history. Reaccept patched immutable products; mandatory additions: patch convergence/old Jackson2 fixture, exact product MCP no-server proof and VEX invalidation, STS denial/KEK secret protection, restore empty/unusual filenames/bad hash/capacity/missing mount/health failure and actual restored quota rejection.
All cases initially NOT_RUN. No VERIFIED until every mandatory case and both promotions pass.

| ID | Assertion | Status |
|---|---|---|
| F01 | Accepted Plan/base and complete strict OpenSpec before code | PASS |
| F02 | Maven module tree/effective POM and scoped BOM; frozen pnpm/Python hashes reject drift | NOT_RUN |
| F03 | All production/probe images and tool versions/checksums/licenses reviewed; dated vulnerability DB with no unresolved high/critical | FAIL: first package scans backend42/frontend2 blocking matches; image/tool audit pending |
| F04 | HTTP/event schema/generation/HC-1/source signature mappings and deviation review | NOT_RUN |
| F05 | Host verify/Vue/strict and actual nonroot Linux SDK/storage/Jackson/permissions/browser | NOT_RUN |
| F06 | gVisor isolation/limits/idle/admission and XFS byte/inode/mount/rebuild/remount | NOT_RUN |
| F07 | >=1800s final candidate observation, dependency failure/recovery, measured RAM/swap/disk | NOT_RUN |
| F08 | Consistent independent restore with hashes/data/quota/readiness; protected evidence | NOT_RUN |
| F09 | Exact legacy identities/policies restored and public entry/backend healthy | NOT_RUN |
| F10 | Hashed evidence local+cloud, CI retention>=30d, no public secrets | NOT_RUN |
| F11 | Exact-head CI/review/two PRs, archive/phase sync/ledger | NOT_RUN |

Production domain/TLS/deployment/tag NOT_RUN by accepted scope; R0 cannot RELEASE or R1 start.

## 2026-10-14 interim evidence
- Security replan now implemented: Boot4.0.8/Tomcat11.0.25/Jackson2.21.7+3.1.7/Vitest4.1.11. HOST44/Vue15/contracts/type/build PASS, actual old writer reports Jackson2.21.1 and patched reader proves scope/manifest/CAS. Six module dependency tree/effective POM completed after a transient Central TLS retry. Raw backend now only MCP advisory; exact SBOM/source/HTTP-bound assessment PASS. Frontend raw high/critical0. No claim of full-stage F03 PASS.
- Initial image audit remains FAIL: Python base74/JRE13/gateway base150/PG418/Redis7/MQ67/SeaweedFS23 high/critical package-advisory matches, requiring individual vendor applicability review. Same current Python Bookworm tag digest unchanged; Debian tracker confirms vulnerable glibc, Trixie fixed. No broad VEX and no hidden unfixed vulnerabilities. Foundation image/distro scope decision pending.
- Three guard tests PASS including mutable identity, exact SBOM invalidation, unrelated critical blocking, empty/special filenames and corrupted content. Restore cleanup installed before mounts, probe mount guard before workspace mkdir, immutable probe identities required. Runtime restore/limits/STS/cloud/soak remain NOT_RUN.
- Plan and strict5/5 before implementation; base/remote main/phase a1c09fea match, original worktree clean.
- Host Maven verify42 tests PASS after unsandboxed retry: first failure was Windows sandbox atomic rename AccessDenied in JUnit temporary directories; preserve both logs. Vue15 tests/type/contracts/frozen-lock/build/links PASS. These are host results only; F05 includes actual Linux/cloud and remains NOT_RUN until complete.
- CycloneDX2.9.3 production+test BOMs and dependency tree/effective POM generated. Syft1.54.1/Grype0.120.1 archives officially hashed; all Python CPython3.12/linux-amd64 direct/transitive wheels locked.
- Grype official DBv6.1.10 built2026-10-13T06:35:20Z. Automatic download failed; curl downloaded official archive and SHA256 ed50c6deedbbe39f815f855fb55208a1f261ebec6a91d2847b1230200bbb7b20 matched before import. Raw backend/frontend reports retained in ignored evidence/r0.4, exit2.
- Package/advisory matches are not confirmed exploit counts. User chose security baseline replan/full reacceptance; no blanket suppression and no fixed framework version changed. Replan is required before dependency remediation.
- Docker Desktop startup/restart has not produced a usable local engine; WSL docker-desktop uname works. No cloud services paused or deployed this step. Backup/restore scripts only bash-n checked; runtime/storage/sandbox/UI/soak/restore gates NOT_RUN for R0.4.
# Runtime packaging follow-up
Go1.26.9:199/199 selected upstream tests PASS at17a8a91; full release FAIL
because clang was absent. Frozen clang/libbpf package installation, complete
release output and runtime security/compatibility acceptance remain NOT_RUN
until the next candidate provides fresh evidence. No test/target is removed.
# Audit tool candidates
Exact source and patched module hashes must pass foundation input checks.
CI core/internal tests and build must pass; version and binary hashes retained.
Tool replacement, actual CLI behavior and binary security acceptance NOT_RUN
until exact candidate evidence passes. Original official tools/raw scans remain.
# STS probe correction
At5964e5b/run38540384541 Docker startup, Linux SDK and four browser cases
PASS. PG/vector, rollback, MQ duplicate/fence, S3 hash/Range/multipart and Redis
PASS before disabled STS failed with ResponseParserError on an empty refusal.
Wire-level denial validator has six passing failure-boundary tests. Signed
body/query HTTP refusal and dependency restart/CAS acceptance must rerun in CI.
The prior failure remains evidence; no scan/cloud acceptance is implied.
# Latest completed CI evidence (2026-10-14)
48233a4d3d59f6d659a2fbb22ae124cce430593e, run38545592217:
runtime job115796235320 PASS including the full gVisor release package.
Toolchain job115796235144 passes host, Docker, Linux SDK, four browser cases,
real PG/vector/rollback/MQ/fence/S3/Redis, six signed STS body/query405 refusals,
S3/Redis restart and Java CAS. Blocking dependency audit FAIL; no image export.
Audit source jobs115796234929/115796235301 FAIL on test prerequisites and root
permission assumptions. Corrected non-root CI/compiler/fixture-cache execution
and four fixture safety tests PASS locally (after Windows sandbox realpath
AccessDenied was rerun outside the sandbox in the same repo temp directory).
Full corrected source candidate tests NOT_RUN until a fresh exact-head run.
R0.4 remains IMPLEMENTING; cloud/runtime limits/XFS/soak/restore NOT_RUN.
# Corrected false CI success signal
20168c9/run38562275076 Syft job115846048738 badge reported success, but raw
logs prove AbsoluteLinkError before tests. tee masked the failed exit code.
This candidate is FAIL and produces no accepted binary. Explicit pipefail and
exact source symlink reconstruction added; five fixture safety tests PASS.
Full source candidates still require fresh tests/build and binary identities.
# Current dependency and CI guard evidence
Unused Testcontainers removal passes all44 Maven/DDD tests. Six module reports
and both scoped SBOMs regenerated; actual external lock is130 artifacts, with
historical139 retained in Git. Testcontainers/Docker Java/HttpCore components
are absent from the fresh test SBOM and generated probe libraries. Five
fixture guards, six STS guards, two foundation guards and two actual candidate
run-block failure injections PASS. The CI guards prove failed commands stop
before acceptance records rather than merely searching for pipefail text.
Final candidate Linux SDK/image scans remain NOT_RUN until the fresh run.
# Frozen ORAS format correction
98aa43c/run38570730851 Syft fails closed at the raw cache_paths.json layer
after frozen layer downloads; directory layers are unpack-marked tar, while
the final fingerprint layer is a raw file. Exact marker handling and a sixth
fixture test added; hashes/paths/source fingerprints remain mandatory.
# Actual rebuilt runtime scan
Run51/da65f88 runtime build FAIL: OCI runtime-spec1.3 changed LinuxPids.Limit to *int64; cgroup and update source still uses integer semantics. No new runtime package accepted. Implement a hashed compatibility patch and add actual upstream cgroup tests; do not roll back containerd security fix or weaken PID admission.
Run38573801766 (b93463e): source Syft fingerprint validation fails closed at classifiers/bin/./config.yaml. Official frozen source stores that config at binary/testdata/config.yaml; generator output nesting is not its working directory. Add ancestor-relative positive coverage and traversal/changed-source rejection before candidate reacceptance. Backend, contracts, Linux SDK, browser and real storage jobs PASS; blocking security audit FAIL, image export skipped. R0.4 remains IMPLEMENTING.
Source48233a4, GitHub ZIP8cd057e6.../runtime package hash verified; all7 package
binaries imported for local scanning, no cloud installation. Raw scan FAIL4:
grpc1.83.1 three matches GHSA-2v4p-qf9q-27wj, shim containerd-v2 2.1.9 one
GHSA-fqw6-gf59-qr4w. Original162-match package scan remains historical, not
current rebuilt result. Fixed module/full-package reacceptance still NOT_RUN.

Ancestor-relative fixture guards: seven tests PASS on Windows with workspace-local temporary files; sandbox-only realpath denial recorded separately, same tests passed outside filesystem sandbox. Original-source hashes remain mandatory. Run51/da65f88 Grype source mod download/verify/test all exit0; runtime patch build still pending, no candidate accepted.

Run52/65e889b Syft fails closed on historical image-single-ipk input. Exact official source Debian test cases enumerate three current dpkg fixtures; three absent historical input/path/hash pairs recorded in foundation lock. Eight fixture tests and two foundation guards PASS, including changed hash, new source and test-reference invalidation. Full source tests still NOT_RUN for this correction.
Grype da65f88 Linux and Windows exact exported binaries/graphs verified against CI ZIP8c177a943...; raw scans each have3high daemon matches. Per-product source/import-graph bound OpenVEX assessments PASS0 unresolved high/critical; remaining medium/unknown reports retained. Complete audit tool and final platform acceptance still blocked.

Shared fixture symlink resolution keeps the global frozen source boundary, rather than rejecting legitimate sibling fixture targets at each ancestor. Windows fixture suite:8 PASS/1 real directory symlink test SKIP; that test is mandatory in Linux CI. PID boundary tests preserve an existing pids.max sentinel for nil/zero/v1-negative cases, reflecting real cgroup files rather than expecting file creation. Full release/cgroup runtime tests remain pending.

# Current approved replacement2026-10-15
Local Docker28.5.1/Linux verified available. All earlier source-candidate failures retained as history, not mandatory vendor repair work. Final exact-source local/cloud SDK/storage/browser/isolation/quota/30min/restore/legacy tests pending. Actions retirement/promotion settings pending. Informational vendor scans do not mean fixed or complete. R0.4 IMPLEMENTING.

Local replacement execution: repository Actions disabled and verified through API; main retired CI removed/PR+non-force retained; previously unprotected phase now has PR/non-force/delete protection and no CI. Maven verify full modules PASS, frontend15/type/build/contracts/links PASS; contract lock8 files PASS. Official API/gateway/storage/sandbox/framework images built locally. Initial Seaweed named-volume UID mismatch fixed only in labelled local fixture volumes; original failure retained. SDK/storage/browser/cloud/restore final gates still pending.
