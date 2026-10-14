# Accepted Plan
Independent Plan completed and user requested implementation on2026-10-14. Canonical R0.4 formal plan is authoritative. Base a1c09fea; branch step/r0.4-contract-evidence-freeze. R0.4 acceptance only: no public domain/TLS or production tag, R0 stays unreleased and R1 cannot start.

## Reproducibility and audit
Use fixed Java21/Boot4.0.4/SDK2.0.4, Maven tree/effective POM per module and separately scoped production/test CycloneDX BOMs. pnpm frozen lock and Python require-hashes include transitive packages. Pin latest official non-prerelease CycloneDX/Syft/Grype at preparation with checksums; do not drift after preparation. Scan actual shipped and probe images, record architecture/registry digest/export config/cloud identity and license provenance. Scan DB version/time is evidence. High/critical findings block; false positives require explicit evidence, genuine risk exceptions human approval. Never hide unfixed vulnerabilities.

## Contract and evidence
Existing health/readiness/Problem/envelope and HC-1 remain baseline; future event payloads are not implemented. Collect SDK signatures/source commit and test method assertions, separating mock/offline from real dependencies. Update stale docs and reviewed deviations. CI protected job name stays R0.1 toolchain. Artifacts retained >=30 days plus protected local/cloud hashed copies; no secrets/raw SQL/logs in public Git.

## Runtime/recovery
Use approved reversible legacy pause and cloud developer acceptance, not production. Run all SDK/storage/permission/UI/gVisor/XFS gates, then fixed candidate >=30min observation. Stop new writers before coherent DB/workspace/SeaweedFS/definitions snapshot, protect credentials separately, and restore to independent temporary projects while original new stack is stopped. Memory/disk admission first; do not run duplicate stacks on minimum host. Verify hash/data/quota/readiness. On any error stop only new stack and restore exact legacy container IDs and saved restart policies. No destructive migration, blanket prune, volume deletion or runc fallback.
