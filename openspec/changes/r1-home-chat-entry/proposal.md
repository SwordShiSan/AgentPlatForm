# R1 home chat entry

## Why
The released R1 frontend still opens the R0 demonstration at / and provides no login/chat entry. Direct /login and /app/chat work. This repair makes the delivered chat reachable from the public root.

## What Changes
- Chat-enabled / and /app lead through the existing authentication guard to chat; successful login uses /app.
- Public /status retains connection checks. Header navigation and chat-enabled copy reflect delivered capabilities.
- Preserve chat-disabled root and account landing. No API/event/schema changes or provider calls.

## Impact
Frontend routing, identity landing, health copy, header CSS, browser tests, immutable gateway delivery and release records. Existing backend/data/accounts remain. Separate step/phase PRs, local/cloud exact-source evidence; Actions disabled. R2.1 is a later independent change.
