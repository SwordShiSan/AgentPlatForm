# Design

Platform-app coordinates transactions/use cases; domain defines identity rules and ports; infrastructure implements JDBC and Argon2; api owns DTOs/use-case interfaces; trigger owns HTTP/cookies. No dependency cycles or SDK-based authorization.

Add Flyway V2 only. pf.baseline_version remains the R0 runtime baseline marker, not the Flyway version. New identity readiness additionally checks required identity relations. Personal-space UUIDs use UUIDv7; username NFKC+Locale.ROOT lowercase, 3–64 code points of letters/digits/._-, password 12–128 characters (never trim).

Provisioning request UUID and SHA256 input fingerprint persist atomically. Bootstrap uses a singleton operation key under an advisory transaction lock. Passwords never appear in process arguments. Session token is 32 random bytes encoded base64url, SHA256 at rest. CSRF uses an independent 32-byte token with hash persisted on a pre-auth/auth session. Pre-auth expires after 15 minutes; successful login replaces both values. Auth sessions use absolute 7d and idle 24h limits, checked against an injected clock. DB rows serialize account failure windows; IP buckets are durable and atomic. Invalid usernames take the same generic login failure path. All error bodies omit SQL, host paths and credentials.

Local identity profile explicitly opts in; default configuration does not expose identity. Local non-Secure cookie mode validates every allowed origin as localhost/127.0.0.1. No identity cloud rollout. UI uses existing Query, Router, generated OpenAPI types and tokens; no new design system.

Provisioning and revocation commands operate through non-web Spring command mode, require an explicit request UUID and a private password file or console, never a default secret. Reset/disable revoke all sessions transactionally. Authorization joins current memberships and user status, ignores platform-admin status for content. No new chat/file endpoints are created for tests.

Additive rollback disables the identity profile and uses R0 code against migrated data; retain new tables/data. Local fixtures have independent names, volumes, ports and credentials. GitHub remains version control only.

Runtime credentials have SELECT only on user/space/membership/storage and DML on sessions/rate buckets. Login and operator revocation share a per-user PostgreSQL advisory transaction lock; login rechecks the password hash/status under that lock. This avoids granting user UPDATE merely to acquire a SELECT FOR UPDATE row lock. Operator-only credentials perform provisioning/revocation. Test and UI fixture databases are separate.
