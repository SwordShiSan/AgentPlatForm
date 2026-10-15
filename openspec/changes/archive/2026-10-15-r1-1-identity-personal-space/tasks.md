## 1. Governance and contracts
- [x] 1.1 Record approved plan/current scope in canonical documents and pass strict.
- [x] 1.2 Add OpenAPI schemas/endpoints, regenerate client, test invalid fixtures.
## 2. Identity implementation
- [x] 2.1 Add V2 constraints, rules/ports, JDBC atomic provisioning and authorization.
- [x] 2.2 Add Argon2, durable sessions/CSRF/limits, revocation and safe operator commands.
- [x] 2.3 Wire opt-in local identity HTTP profile, sanitized errors and readiness.
## 3. UI
- [x] 3.1 Implement login, account shell, menu, expiry/logout/cache handling.
## 4. Acceptance
- [x] 4.1 Run unit/architecture/contract/frontend/strict regression.
- [x] 4.2 Run real PG fresh/upgrade/rollback/concurrency/isolation/expiry/revocation tests.
- [x] 4.3 Run local Docker/API/browser tests and restart/dependency failure/resource checks.
## 5. Promotion
- [x] 5.1 Bind evidence to exact source/image, review, merge step→phase→main.
- [x] 5.2 Archive change, sync phase and update canonical ledgers/handoff without cloud deployment.
