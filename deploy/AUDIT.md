# R0.4 audited baseline

## Current approved policy2026-10-15
Local/cloud functional acceptance replaces hosted builds. Repository Actions are disabled; PR/non-force protections remain and retired CI contexts are removed. Official locked Syft/Grype reports and raw findings are informational where unrelated to required project functionality/isolation; findings are not claimed fixed. Vendor source rebuilds, global vulnerability clearance and package ownership collection are no longer mandatory. Use stable R0.3 official middleware digests, official gVisor20261005.0/systrap and the locally built application/probe/sandbox images. Functional, isolation, quota and recovery gates remain mandatory. The security-rebuild requirements and failures below are preserved history, superseded by this policy. R0 remains unreleased; no production tag or R1.

## Historical security replan and evidence (2026-10-14)
Boot4.0.8/Tomcat11.0.25/Jackson2.21.7+3.1.7/Vitest4.1.11 are implemented; Java21/SDK2.0.4/schema1 unchanged. HOST44 (including real old-Jackson2.21.1 fixture readback and real GET/POST MCP denial), Vue15/type/build/contracts PASS. Six modules have actual dependency tree/effective POM. Package raw scan now has only exact MCP GHSA-8jxr-pr72-r468; product/SBOM/source/HTTP-bound assessment PASS with raw retained. Frontend raw scan has no high/critical matches. Guard tests reject mutable products, changed SBOM, unrelated critical, bad workspace hashes and preserve empty/special-name JSON manifests.

Current foundation replan is approved: Trixie/Noble/Alpine3.24, compatible patched rebuilds and Go1.27.2. `foundation.lock.json` pins linux/amd64 inputs, exact source commits, archives and module patches; `foundation-inputs.py` rejects drift before offline-package builds. Redis7.4 is RSALv2 OR SSPL-1.0. Whole-stage audit remains FAIL: new raw foundation scans still require individual package/file ownership and vendor status review. Never hide unfixed findings. `assess-applicability.py` accepts only individually evidenced, product/report/SBOM/source-bound OpenVEX statements; unrelated critical findings remain blocking. No cloud acceptance or VERIFIED yet. Counts represent package/advisory matches, not independent confirmed exploits. Historical reports remain available.

The preparation history below predates this security replan and does not override this current section.

R0.4 is IMPLEMENTING. Accepted preparation baseline is a1c09fea; no production release or R1 start. The user-approved foundation repair plan is being implemented; full image/security and cloud reacceptance remain blocking. Earlier preparation/replan statements below are historical.

2026-10-14 current evidence: da65f88 Grype source tests and Linux/Windows builds pass. CI ZIP SHA256 8c177a943578f6fbd01923c86a6e257d695308b6b0fae31ea14427a10548e505 and exported binary/import-graph identities were verified before local use. Both raw binary scans retain three high Docker-module matches. Exact per-product OpenVEX reviews prove daemon/API-server/AuthZ packages absent from the matching build graphs; assessed high/critical0 for these Grype binaries only. Medium/unknown findings remain in raw reports. This is not a host Docker assessment or complete toolchain acceptance; Syft source acceptance remains blocked.

Run51 runtime security module update fails full compilation at OCI1.3 PID pointer fields. a46ec0a adds a hashed compatibility patch and cgroup tests for nil/zero/128/unlimited/systemd/controller rejection; fresh full release build and binary scan are required. No cloud runtime installation or old-stack pause occurred.

Use `audit-tools.lock.json`, not latest download in CI. `prepare-audit-tools.py` resolves official stable releases once; `install-audit-tools.py` verifies the locked official archive hashes. CycloneDX2.9.3, Syft1.54.1, Grype0.120.1 are pinned. Artifact retention is30days.

From backend use Wrapper `-Paudit-sbom cyclonedx:makeAggregateBom -DincludeTestScope=false -DoutputName=bom-production`, then the same goal with includeTestScope=true/outputName=bom-with-tests. Per-module dependency reports are separate from packaged runtime SBOM; test/probe dependencies must also be audited.

`audit-baseline.py --target label=SOURCE` generates both Syft and CycloneDX reports and uses `--fail-on high` without ignoring unfixed packages. `grype db status -o json` records freshness. Missing DB is NOT_RUN; high/critical matches are FAIL until verified remediation or reviewed non-applicability. No blanket suppressions.

`freeze-contracts.py` checks HTTP/event envelope, actual generated TS and HC-1 source hashes. A deliberate contract change requires Plan/OpenSpec update and reacceptance before `--write`. Existing event payloads remain future capability, not delivered business events.

2026-10-14 first scan: backend42 high/critical package/advisory matches, frontend2, not42 independent confirmed exploits. Official DBv6.1.10 built2026-10-13T06:35:20Z, official archiveSHA256 ed50c6deedbbe39f815f855fb55208a1f261ebec6a91d2847b1230200bbb7b20 verified before manual import. Full JSON is ignored evidence/r0.4; individual applicability review is still required. User selected security-patch baseline revision and full reacceptance. Fixed Boot4.0.4/SDK2.0.4 have NOT been silently changed.

Cloud backup/restore scripts are implementation candidates, NOT_RUN. They stop writers, create a coherent cold new-stack snapshot and restore to distinct roots/projects/networks/port18082 with a copied XFS image UUID. They preserve old services/data and snapshots. Only execute after audit/replan and all preconditions are satisfied. They are not production backup evidence, offsite protection or R9 recovery certification.

`evidence-index.py` hashes an explicit report whitelist. Credentials, raw SQL and protected cloud operation directories are excluded. Canonical body/ledgers live outside Git and must be handed off separately.
