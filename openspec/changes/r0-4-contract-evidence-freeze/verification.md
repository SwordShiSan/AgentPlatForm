# Verification
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
- Plan and strict5/5 before implementation; base/remote main/phase a1c09fea match, original worktree clean.
- Host Maven verify42 tests PASS after unsandboxed retry: first failure was Windows sandbox atomic rename AccessDenied in JUnit temporary directories; preserve both logs. Vue15 tests/type/contracts/frozen-lock/build/links PASS. These are host results only; F05 includes actual Linux/cloud and remains NOT_RUN until complete.
- CycloneDX2.9.3 production+test BOMs and dependency tree/effective POM generated. Syft1.54.1/Grype0.120.1 archives officially hashed; all Python CPython3.12/linux-amd64 direct/transitive wheels locked.
- Grype official DBv6.1.10 built2026-10-13T06:35:20Z. Automatic download failed; curl downloaded official archive and SHA256 ed50c6deedbbe39f815f855fb55208a1f261ebec6a91d2847b1230200bbb7b20 matched before import. Raw backend/frontend reports retained in ignored evidence/r0.4, exit2.
- Package/advisory matches are not confirmed exploit counts. User chose security baseline replan/full reacceptance; no blanket suppression and no fixed framework version changed. Replan is required before dependency remediation.
- Docker Desktop startup/restart has not produced a usable local engine; WSL docker-desktop uname works. No cloud services paused or deployed this step. Backup/restore scripts only bash-n checked; runtime/storage/sandbox/UI/soak/restore gates NOT_RUN for R0.4.
