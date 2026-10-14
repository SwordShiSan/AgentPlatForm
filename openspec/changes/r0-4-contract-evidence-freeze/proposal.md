# Why
R0.1–R0.3 are verified and archived at a1c09fea. R0.4 must freeze reproducible dependencies and contracts and preserve reviewable audit, runtime and recovery evidence.

# What Changes
- Patch actual rebuilt runtime matches with grpc1.83.2/containerd-v2 2.2.4 and fresh locked module/full-runtime tests; preserve Go1.26.9 and complete package.
- Remove unused test-scoped Testcontainers after SBOM ownership identifies its shaded HttpCore matches; clean generated probe classpaths, refresh actual locks/production+test audits, and keep all existing SDK/Docker/storage tests.
- Refine disabled STS acceptance to signed HTTP status/body checks for the actual IAM-disabled route; empty405 is allowed only with working S3 checks, and transport/success responses remain failures.
- Approved foundation replan continues5316ba6: Python3.12.15/Node22.23.3 on Trixie, Temurin21.0.12.1 on Noble, Nginx1.30.5/Alpine3.24, PG17.11+vector0.8.1, Redis7.4.11, RabbitMQ4.3.6 and security-rebuilt SeaweedFS4.48. Host OS unchanged. Exact sources/manifests/packages/patch hashes and full reacceptance precede promotion.
- Approved replan replaces Boot4.0.4 with Boot4.0.8/Tomcat11.0.25/Jackson2.21.7+3.1.7/Vitest4.1.11; SDK2.0.4 remains. Artifact-bound MCP non-applicability needs actual no-server proof, not blanket ignore.
- Pin complete dependencies, audit tools and CI actions; generate module dependency reports and production/test/image SBOMs and vulnerability/license reports.
- Freeze current HTTP/event/HC-1 contracts and connect real SDK signatures to source and executable cases.
- Add protected evidence packaging and isolated consistent backup/restore rehearsal; rerun all R0 cloud gates.
- Reconcile canonical documents and status, without marking R0 production released.

# Capabilities
## New Capabilities
- `contract-evidence-freeze`: reproducible audited baseline and durable acceptance evidence.
## Modified Capabilities
None.

# Impact
Backend build tooling, frontend contract documentation, probe dependency lock, deploy and CI. No business API, schema migration or legacy data migration. Single-slot gVisor/XFS and resource caps persist. Two-level PR promotion only after all mandatory gates pass.
