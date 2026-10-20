# Why
Future cancellation currently completes before transport teardown is proven, and UNKNOWN calls have no auditable resolution workflow. Preserve execution ownership until quiescence is verified and distinguish local cancellation from remote execution/billing facts.
# What Changes
Add V8 cancellation/stop-receipt/reconciliation records and call revision. Use existing MyBatis/Spring transactions, AMQP and AgentScope2.0.4; explicit result/cancel/termination ports. Add an authorized read-only reconciliation projection and protected evidence-based operator commands. User cancellation remains compatible; UNKNOWN never replays and partial remains visible.
# Impact
Local only, baseline7598163; no edits toV1-V7/schema1. Zero real supplier calls, cloudR1 unchanged, no production tools/pause/checkpoint continuation. Single execution slot;2528MiB initial/2887 ceiling/768 host reserve. Approved: operator-only reconciliation and independent new messages after proven stop, with unknown rounds explicitly excluded from context.
