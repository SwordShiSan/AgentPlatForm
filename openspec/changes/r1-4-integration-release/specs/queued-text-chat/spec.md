## MODIFIED Requirements

### Requirement: Local constrained acceptance
The platform SHALL use the approved DeepSeek model and real RabbitMQ/PostgreSQL acceptance under one execution slot. Per-service memory MAY be redistributed within2887MiB enabled-container limit and3655MiB host budget. Real acceptance SHALL minimize physical calls, preserve cumulative metering/UNKNOWN holds and stop at provider remote quota or balance exhaustion without a fixed20-call/1-CNY ceiling. Business budgets remain enforced. Cloud production SHALL remain R0 until the separate R1.4 release gates pass.
#### Scenario: Remote provider quota exhausted
- **WHEN** the provider reports exhausted remote balance or quota
- **THEN** acceptance stops without retries or credential rotation to evade the limit; incomplete cases remain NOT_RUN
