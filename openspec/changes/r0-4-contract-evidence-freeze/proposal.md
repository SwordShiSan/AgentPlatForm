# Why
R0.1–R0.3 are verified and archived at a1c09fea. R0.4 must freeze reproducible dependencies and contracts and preserve reviewable audit, runtime and recovery evidence.

# What Changes
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
