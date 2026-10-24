## 1. Readiness and experiments
- [x] 1.1 Approved Plan and five artifacts, baseline inspection and updated architectural scope.
- [x] 1.2 Strict validation before business code; SDK/BOM lock and Enforcer compatibility.
- [x] 1.3 Bounded100MiB SDK/helper streaming and atomic replacement/crash experiments.
## 2. Persistence and storage
- [x] 2.1 Additive V13 and typed Mapper/PO/XML, scoped constraints and quota admission.
- [x] 2.2 Managed S3 streaming, multipart failure handling, hash/range and exact reservations.
  - Include cold non-empty multipart admission with metadata and asset collections; HEAD alone is not data-plane readiness.
- [x] 2.3 Upload lifecycle, positive-only staged-object recovery, immutable versions, signed runner and query-only reconciliation.
- [x] 2.4 Shared workspace lease/auxiliary stop facts independent of environment.
## 3. Public product
- [x] 3.1 OpenAPI/events and generated client, DTO/validation/auth/CSRF/preconditions.
- [x] 3.2 Directory/read/download/usage and durable scan projections.
- [x] 3.3 Vue upload/explicit replace/conflict/preview/download/operations and accessibility.
## 4. Acceptance
- [x] 4.1 Engineering/migration/R3.2 compatibility and persistence checks.
- [x] 4.2 Real PG/MQ/S3 faults, concurrency, accounting and duplicate delivery.
- [x] 4.3 Real XFS/helper boundary/stop/rebuild and scoped browser matrix.
- [x] 4.4 Final >=1800s observation and exact R2 instance/mount/restart restoration, vendor calls0.
- [x] 4.5 Final authenticated R2 login/history read-only verification via explicitly approved SSH loopback forwarding; login/history/isolation/logout/readiness/private endpoints passed, no new supplier calls. Original rejected public HTTP action was never executed.
## 5. Closure
- [x] 5.1 Exact-source evidence and protected step->phase->main, phase sync.
- [x] 5.2 Archive, canonical ledgers/ADR/Skill and protected delivery hash copies; all fixture profiles stopped.
