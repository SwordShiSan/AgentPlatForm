# R0.4 local functional acceptance and evidence freeze

## Why
Close the engineering baseline with real local/cloud function and recovery evidence. The user approved this replacement plan on2026-10-15; blanket vulnerability remediation and third-party source rebuilds no longer define acceptance.

## What Changes
- Local Docker builds and developer acceptance; GitHub version management only, Actions disabled and retired CI checks removed while PR/non-force protections remain.
- Official fixed-identity middleware from stable a1c09fea; existing app patches/Java21/AgentScope2.0.4/schema1 preserved.
- Official gVisor20261005.0 systrap package; no candidate source patches or runc fallback.
- Optional official Syft/Grype reporting; unrelated vendor findings retained, not falsely resolved or treated as function failures.
- Full SDK/storage/browser/isolation/quota/30min/serial restore and legacy recovery evidence before two PRs.

## Impact
No new public endpoints, schema migrations, production tag, R0 release or R1. Third-party source repair/costly rebuild needs prior discussion. Previous failure evidence is historical and retained.
