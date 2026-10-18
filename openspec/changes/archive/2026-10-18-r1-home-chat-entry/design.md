# Design

Use the existing compile-time VITE_CHAT_ENABLED decision for root and /app redirects. Login replaces /app, keeping a single default landing rule and avoiding arbitrary redirect query inputs. Register /status independently. Protected target guards retain existing fail-closed behavior.

Header chat/account links use distinct accessible names from existing sidebar links. Responsive navigation wraps and uses >=44px targets. Chat-enabled health/account copy reflects R1; flag-off root remains compatible. Query/Pinia authorities and identity cleanup are unchanged.

Tests exercise both compiled flag values, real local Docker API, two independent browser contexts, session revocation, existing deep-link/SSE reads, dependency failure, responsive/themes/zoom/keyboard. Update all existing test assumptions that depended on login entering account settings. No provider requests are required.

No migrations or backend builds. Build/export the gateway locally with revision/platform/index-manifest-config-rootfs identity chain, verify archive hashes on import. Save current gateway/config/other container identities; deploy gateway with --no-deps and original32MiB limit. Require >=5GiB root disk after staging. Browser failure restores prior gateway/config. Tag only after cloud smoke. Update immutable artifact evidence, private local/cloud delivery and canonical docs separately from Git. R2.1 requires a later approved Plan/OpenSpec.
