# Why

R0.2 is verified at a6d7d5d. R0.3 needs real storage/dispatch dependencies and a reproducible isolated Linux acceptance environment. The user approved using the existing 4-vCPU/3655-MiB/40-GiB cloud host, pausing legacy services rather than adding hardware.

# What Changes

- Add least-privilege PostgreSQL/Redis/RabbitMQ/SeaweedFS environment and API/worker/runner/egress profiles, migrations, readiness and internal observations.
- Add real transaction/CAS, AMQP confirm/handoff/fence and S3 restart/range/hash/multipart probes.
- Bound host resource use to one task and one on-demand gVisor sandbox; use a separately mounted preallocated XFS image with project quotas.
- Inventory and precisely clean obsolete images/logs, preserve current and rollback images/data, and provide reversible legacy pause/restore scripts.
- Add Vue tokens, responsive shell and error states; change canonical deployment/ADR/R0/R3/R9 documents to cloud development acceptance. Production release remains a separate gate.

# Capabilities

## New Capabilities
- `storage-deployment-baseline`: resource isolation, storage integration, operational recovery and cloud acceptance.

## Modified Capabilities
None. Existing framework and health contracts remain intact.

# Impact

Six-module backend, frontend, contracts, deploy, CI and canonical external docs. No legacy source/data migration. Legacy cloud pause and maintenance are explicitly user-authorized; data volumes are never pruned. Public shell/model/task features remain disabled until their scheduled gates.
