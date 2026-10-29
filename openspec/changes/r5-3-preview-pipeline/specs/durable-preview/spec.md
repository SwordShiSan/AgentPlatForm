## ADDED Requirements

### Requirement: Version-pinned authorized preview
The platform SHALL accept a real immutable file version, bounded format and idempotency key, return a stable preview identity, and authorize every create/query/content/retry against current source access. Cache reuse SHALL be limited to the same space, version, renderer bundle and policy. Original downloads SHALL retain attachment semantics.

#### Scenario: Cache reuse and source movement
- **WHEN** an authorized user previews the same version twice and the source is later moved or tombstoned
- **THEN** the same valid cache can serve the original bytes without a second conversion or object charge, while source-version access is checked again

#### Scenario: Revoked or unrelated reader
- **WHEN** a different-space reader or revoked member requests a cached preview
- **THEN** metadata and bytes are refused regardless of administrator role or knowledge of a hash

### Requirement: Durable single-owner conversion
The platform SHALL use admission/reservation plus outbox, RabbitMQ confirms, persistent claim before manual ACK, signed runner, stopped physical receipt and a fenced commit. No network or rendering wait SHALL occur inside a database transaction. READY SHALL require validated output and actual termination; uncertain work SHALL remain BLOCKED and retain its reservations.

#### Scenario: Lost response after output exists
- **WHEN** a runner response or database commit is lost after conversion
- **THEN** reconciliation queries the original command and publishes at most one result and settlement without a blind new conversion

#### Scenario: Known infrastructure failure
- **WHEN** an infrastructure attempt has definitely failed and its exact process has stopped
- **THEN** at most one automatic retry may create a fresh attempt; malformed, unsupported and encrypted inputs are not retried automatically

### Requirement: Isolated bounded renderer
The platform SHALL use a fixed non-root read-only gVisor renderer without network, credentials, Docker socket or workspace mounts. One read-only input and bounded scratch/output are allowed. Input100MiB, output100MiB, pages200, expandedOffice500MiB, image40MP and active120seconds are rejection ceilings, not universal successful-capacity promises. Source/output transfer SHALL remain bounded and sequential.

#### Scenario: Invalid or exhausting input
- **WHEN** a corrupt, encrypted, unsupported or resource-exhausting document is submitted
- **THEN** a stable failure is recorded, the platform remains alive and original download stays available; unconfirmed stop does not release capacity

### Requirement: Shared heavy admission
Business sandbox preparation, wake, shell and renderer SHALL acquire the same durable heavy admission before physical start. Preview SHALL not invent task/workspace/environment identities or take an immutable source's workspace write lease. Total budget SHALL remain at most2887MiB with768MiB host reserve and5GiB root reserve.

#### Scenario: Sandbox already active
- **WHEN** conversion is queued while a business sandbox owns heavy capacity
- **THEN** conversion waits without starting another heavy container or forcibly preempting the active environment; ordinary chat and existing downloads remain available

#### Scenario: Preview already active
- **WHEN** a valid environment preparation or wake is accepted while conversion owns heavy capacity
- **THEN** its original environment operation waits durably and claims once after confirmed preview stop, without a second heavy container or loss of its publication

### Requirement: Safe multi-format display
The UI SHALL share a pinned preview across current versions, history and artifacts. DOCX/XLSX/PPTX convert toPDF; PDF uses local PDF.js with serial Range; PNG/JPEG/GIF-first-frame/WebP use validated image output; HTML defaults to source and static preview uses a sanitized opaque sandbox. SVG and legacy Office remain source/download only. Closing a viewer SHALL abort reads but not erase durable work.

#### Scenario: Static HTML
- **WHEN** the user explicitly opens HTML preview
- **THEN** scripts, external resources, active forms and same-origin privileges are unavailable and original content remains downloadable

#### Scenario: Logout and retry conflict
- **WHEN** identity expires or a retry uses stale revision
- **THEN** viewer reads/cache are cleared or retry is rejected without repeating an unknown operation
