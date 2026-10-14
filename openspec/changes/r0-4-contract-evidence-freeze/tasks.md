## 1. Preparation
- [x] 1.1 Record accepted Plan, baseline and strict validation before implementation.
## 2. Reproducible audit
- [ ] 2.1 Pin tools/actions and Python transitive hashes; generate dependency/effective-POM/SBOM and image/license inventory.
- [ ] 2.2 Scan dependencies/images with dated DB; resolve all blocking findings and review STS/SSE exposure.
## 3. Contracts and evidence
- [ ] 3.1 Freeze schemas and generated client; collect source signatures and scenario mappings.
- [ ] 3.2 Protect hashed local/cloud evidence with >=30-day CI retention; synchronize canonical docs.
## 4. Acceptance
- [ ] 4.1 Host backend/frontend/contracts/OpenSpec gates.
- [ ] 4.2 Real Linux SDK/storage/permissions/browser/gVisor/XFS gates.
- [ ] 4.3 Fixed candidate >=30min observation and dependency failures.
- [ ] 4.4 Consistent isolated restore and legacy pause/restore rehearsal.
## 5. Promotion
- [ ] 5.1 Review exact-head passing CI and step→phase→main PRs.
- [ ] 5.2 Sync phase, archive and reconcile ledgers; R0 remains unreleased.
