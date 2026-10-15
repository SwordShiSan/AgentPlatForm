# R1.1 Identity and personal space

## Why
R0 is RELEASED. The platform needs durable identities before chat can be delivered, without guest administrator shortcuts or cross-space access.

## What Changes
- Transactional operator provisioning of users, personal spaces, memberships and storage policies.
- Hashed opaque sessions, pre-login CSRF, origin validation, expiry, revocation and bounded password work.
- Contract-generated login/account Vue UI and real PostgreSQL/browser acceptance.
- Local acceptance only; production remains R0. No anonymous registration, organizations, models, task or file APIs.

## Capabilities
### New Capabilities
- `identity-personal-space`: Provisioning, authentication and personal-space isolation.
### Modified Capabilities

## Impact
Six-module DDD application, additive V2 migration, OpenAPI, Vue and isolated local Docker fixtures. No hosted CI, cloud changes, DNS/TLS work or third-party source rebuilding.
