# Why
R4.2 has scoped policy but production BUSINESS ASK still becomes TERMINAL/MODEL_INCOMPLETE. ConfirmResult alone is not durable platform authorization. This step closes exact-call approval, denial and recovery without enabling production tools.
# What Changes
- Reuse task_interaction/answer/resume and owned tool ledger. Add V17 immutable approval binding/consumption and WAITING_APPROVAL checkpoint; task remains WAITING_INPUT.
- One BUSINESS per round, no CONTROL mixing; exact ALLOW_ONCE or DENY, no scope grant in approval card.
- Restore original checkpoint before operation mutation; consume once in startTool transaction; persist zero-effect DENIED and canonical history.
- Typed approval pending/API/events and ApprovalCard. Protected deterministic provider/tools only, supplier calls0, cloudR3 unchanged.
# Capabilities
## New Capabilities
- `exact-tool-approval`: durable exact original-call review and recovery.
## Modified Capabilities
None. Existing Plan/Question and task permissions remain compatible.
# Impact
Java21/Boot4.0.8/AgentScope2.0.4, six modules, MyBatis/Spring transactions/AMQP unchanged. Add V17 only, runtime baseline schema1. No cloud/sandbox/production tools, no third-party source changes.
