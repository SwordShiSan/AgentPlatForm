# R1.3 chat drafts, immutable configuration and user credentials
## Why
R1.2 is VERIFIED at main ae211d9. Finish the chat configuration experience without changing execution ownership or deploying R1.
## What Changes
- Browser-local drafts; atomic first-message submission with existing POST compatibility.
- Flash/Pro selection, optimistic personal preferences, resolved configuration and immutable task snapshots.
- Scoped USER credential metadata/write/revoke endpoints; dedicated API encryption service and worker-only runtime decryption.
- Sanitized audit correlation and public TEXT block boundary regression.
## Capabilities
### New Capabilities
- `chat-config-snapshot`: Drafts, configuration resolution, credential lifecycle and acceptance.
## Impact
Additive V4; V1–V3 unchanged. Six DDD modules, OpenAPI/generated client, Vue and local deployment. No hosted CI, cloud changes, tools, production tag or R1.4 implementation.
