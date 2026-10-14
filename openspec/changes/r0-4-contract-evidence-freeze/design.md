# Accepted Plan

## Audit candidate test environment correction
The first source candidates failed because Docker-build RUN lacked Docker/zip/
zstd, Git repository discovery and generated fixtures; root also invalidated
permission-denial tests. Run the same complete core/internal suites as the
non-root CI runner, using Go1.27.2 copied from the frozen compiler image and
verified source/module hashes. Check host Docker/zip/zstd identity, record
versions/checksums and fail when tools are missing. Reconstruct a local Git
root solely for upstream root discovery; source provenance remains the exact
official archive/commit plus recorded patches, never the synthetic Git HEAD.
Use the official Syft fixture OCI cache pinned at preparation by registry digest,
all layer hashes and source-input fingerprints; mismatch fails rather than
using stale fixtures or fetching a floating replacement. Build and retain
candidate output only after tests pass. CI Docker access is for disposable
test fixtures; no cloud build or sandbox Docker socket access is introduced.

## Disabled STS wire-level acceptance
SeaweedFS4.48 with IAM disabled and no STS handlers registers only GET at
the root; signed STS POST requests can return empty405 rather than AWS XML.
The probe must verify the signed HTTP response itself: empty405 for missing
routes, or XML AccessDenied403/ServiceUnavailable503. Test body/query actions,
reject every success/token/identity response, malformed other errors and all
transport failures. Confirm working S3 access before/after so a broken service
cannot pass this denial gate. No STS capability or public contract is added.

## Audit tool source candidates
Preserve the official Syft1.54.1/Grype0.120.1 bootstrap tools and their raw
reports. Build separate disclosed candidates from their exact official commits,
Go1.27.2 and frozen x/net0.60.0 module patches. CI tests the complete core and
internal package suites before building with upstream Linux release tags.
Record source/module/license/binary hashes and version output. These candidate
binaries cannot replace the accepted tool lock until tests, actual CLI behavior,
SBOM and security review pass; a source build is not a scan acceptance.

## Frozen runtime build dependencies (2026-10-14)
The Go1.26.9 fallback passed all199 selected upstream buffer/network tests,
including static analysis. Full upstream release packaging then required clang
and libbpf headers. Freeze their Debian13 package closure, versions and SHA256
from the same saved official index in the container builder; install offline
with dpkg and verify clang/BPF headers before Bazel. Preserve the full release
target, tests and lock enforcement. These are build dependencies, not cloud
host packages. A successful test subset does not accept the runtime candidate.

## Current foundation replan (2026-10-14; supersedes historical fixed bases)
Continue5316ba6 under the same change. Container distribution changes are authorized; host OS unchanged. Python3.12.15 and Node22.23.3 use Debian13 Trixie; Temurin21.0.12.1 uses Noble; gateway Nginx1.30.5 uses Alpine3.24, user101 and8080. PostgreSQL17.11/Trixie builds pgvector0.8.1 without schema migration. Redis stays7.4 (start7.4.11), RabbitMQ4.3.6 retains plugins/topology; SeaweedFS4.48 is security-rebuilt without changing storage format. Prefer Go1.27.2; supported1.26 fallback only1.26.9 with project tests. Pin platform manifest, source commit, package versions and patch checksums before builds. No floating source/ref or cloud compilation/scanning. Local Docker failure routes to CI immutable exports.

Raw reports remain mandatory. Generalized OpenVEX requires exact product/component/advisory/source/evidence hashes and invalidation on drift; vendor backports require actual file/package ownership and official status. Unfixed/wont-fix and genuine unresolved high/critical remain blocking. Redis7.4 license is RSALv2/SSPLv1. Audit actual tools/runsc binaries; disclose source rebuilds. Final candidate requires all fresh host/Linux/storage/UI/gVisor/XFS gates,>=1800s observation, independent serial restore, protected key recovery and legacy restoration. Maintain5GiB root reserve. R0.4 remains IMPLEMENTING until all mandatory gates and two exact-head PRs pass; R0 remains unreleased.

## Accepted security replan (2026-10-14; supersedes original Boot4.0.4 below)
User completed independent Plan and approved implementation from16677a8. Java21/SDK2.0.4/schema1 unchanged; Boot4.0.8/BOM Spring7.0.9/Micrometer1.16.7/pgjdbc42.7.13; overrides Tomcat11.0.25/Jackson2 BOM2.21.7/Jackson3 BOM3.1.7; Vitest4.1.11 retains Node22/Vite7. No business/API/migration enablement. MCP0.17.2 remains SDK-compatible: prove no SDK server/transport entry in each platform/probe product, then artifact-bound OpenVEX for GHSA-8jxr-pr72-r468 only. Preserve raw/assessed scans; source/dependency/entry changes invalidate proof. Other high/critical findings block. Disable SeaweedFS embedded IAM, assert STS unavailable, wrap KEK with separately backed-up protected secret.

Re-run all original mandatory gates, including30min and isolated restore. Build/scan locally or CI, never cloud. Every module effective POM/tree; production/test/base/probe/tool SBOM and archive/binary/plugin/image identity hashes. Install restore traps before mounts/start, JSON workspace manifest supports empty/unusual names; actual hard quota/missing mount on copied XFS UUID. Preserve5GiB reserve, single-stack serial restore and legacy recovery. R0 remains unreleased.
Independent Plan completed and user requested implementation on2026-10-14. Canonical R0.4 formal plan is authoritative. Base a1c09fea; branch step/r0.4-contract-evidence-freeze. R0.4 acceptance only: no public domain/TLS or production tag, R0 stays unreleased and R1 cannot start.

## Reproducibility and audit
Use fixed Java21/Boot4.0.4/SDK2.0.4, Maven tree/effective POM per module and separately scoped production/test CycloneDX BOMs. pnpm frozen lock and Python require-hashes include transitive packages. Pin latest official non-prerelease CycloneDX/Syft/Grype at preparation with checksums; do not drift after preparation. Scan actual shipped and probe images, record architecture/registry digest/export config/cloud identity and license provenance. Scan DB version/time is evidence. High/critical findings block; false positives require explicit evidence, genuine risk exceptions human approval. Never hide unfixed vulnerabilities.

## Contract and evidence
Existing health/readiness/Problem/envelope and HC-1 remain baseline; future event payloads are not implemented. Collect SDK signatures/source commit and test method assertions, separating mock/offline from real dependencies. Update stale docs and reviewed deviations. CI protected job name stays R0.1 toolchain. Artifacts retained >=30 days plus protected local/cloud hashed copies; no secrets/raw SQL/logs in public Git.

## Runtime/recovery
Use approved reversible legacy pause and cloud developer acceptance, not production. Run all SDK/storage/permission/UI/gVisor/XFS gates, then fixed candidate >=30min observation. Stop new writers before coherent DB/workspace/SeaweedFS/definitions snapshot, protect credentials separately, and restore to independent temporary projects while original new stack is stopped. Memory/disk admission first; do not run duplicate stacks on minimum host. Verify hash/data/quota/readiness. On any error stop only new stack and restore exact legacy container IDs and saved restart policies. No destructive migration, blanket prune, volume deletion or runc fallback.
