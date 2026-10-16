## ADDED Requirements
### Requirement: Explicit text bundle and real state compatibility
The platform SHALL admit only the historical scripted bundle or the frozen DeepSeek text-stream bundle, persist its actual manifest hash, and use exact nonnegative CAS versions for SDK private state in PostgreSQL.
#### Scenario: Real database private state save
- **WHEN** Harness saves unversioned private state during a text attempt
- **THEN** the adapter reads its current slot version and performs exact CAS without a negative sentinel or blind overwrite
#### Scenario: Unknown bundle or stale state version
- **WHEN** an unregistered model/component combination or stale CAS version is supplied
- **THEN** composition or state writing fails, and the historical scripted bundle remains compatible
### Requirement: Streaming model port compatibility
The framework adapter SHALL support live asynchronous text deltas and downstream cancellation without exposing SDK types in domain ports. Existing complete-only probes SHALL remain compatible; production streaming SHALL NOT collect all SDK events before completion.
#### Scenario: Slow model stream and cancellation
- **WHEN** the model emits text before completion or is cancelled
- **THEN** deltas reach the platform while running and cancellation closes owned resources without emitting hidden reasoning
