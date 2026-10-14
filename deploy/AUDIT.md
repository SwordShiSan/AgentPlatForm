# R0.4 audited baseline

R0.4 is IMPLEMENTING. Accepted preparation baseline is a1c09fea; no production release or R1 start. Full acceptance remains blocked pending security-baseline replan.

Use `audit-tools.lock.json`, not latest download in CI. `prepare-audit-tools.py` resolves official stable releases once; `install-audit-tools.py` verifies the locked official archive hashes. CycloneDX2.9.3, Syft1.54.1, Grype0.120.1 are pinned. Artifact retention is30days.

From backend use Wrapper `-Paudit-sbom cyclonedx:makeAggregateBom -DincludeTestScope=false -DoutputName=bom-production`, then the same goal with includeTestScope=true/outputName=bom-with-tests. Per-module dependency reports are separate from packaged runtime SBOM; test/probe dependencies must also be audited.

`audit-baseline.py --target label=SOURCE` generates both Syft and CycloneDX reports and uses `--fail-on high` without ignoring unfixed packages. `grype db status -o json` records freshness. Missing DB is NOT_RUN; high/critical matches are FAIL until verified remediation or reviewed non-applicability. No blanket suppressions.

`freeze-contracts.py` checks HTTP/event envelope, actual generated TS and HC-1 source hashes. A deliberate contract change requires Plan/OpenSpec update and reacceptance before `--write`. Existing event payloads remain future capability, not delivered business events.

2026-10-14 first scan: backend42 high/critical package/advisory matches, frontend2, not42 independent confirmed exploits. Official DBv6.1.10 built2026-10-13T06:35:20Z, official archiveSHA256 ed50c6deedbbe39f815f855fb55208a1f261ebec6a91d2847b1230200bbb7b20 verified before manual import. Full JSON is ignored evidence/r0.4; individual applicability review is still required. User selected security-patch baseline revision and full reacceptance. Fixed Boot4.0.4/SDK2.0.4 have NOT been silently changed.

Cloud backup/restore scripts are implementation candidates, NOT_RUN. They stop writers, create a coherent cold new-stack snapshot and restore to distinct roots/projects/networks/port18082 with a copied XFS image UUID. They preserve old services/data and snapshots. Only execute after audit/replan and all preconditions are satisfied. They are not production backup evidence, offsite protection or R9 recovery certification.

`evidence-index.py` hashes an explicit report whitelist. Credentials, raw SQL and protected cloud operation directories are excluded. Canonical body/ledgers live outside Git and must be handed off separately.
