## 1. Governance and contracts
- [ ] 1.1 Record approved plan/current scope in canonical documents and pass strict.
- [ ] 1.2 Add OpenAPI schemas/endpoints, regenerate client, test invalid fixtures.
## 2. Identity implementation
- [ ] 2.1 Add V2 constraints, rules/ports, JDBC atomic provisioning and authorization.
- [ ] 2.2 Add Argon2, durable sessions/CSRF/limits, revocation and safe operator commands.
- [ ] 2.3 Wire opt-in local identity HTTP profile, sanitized errors and readiness.
## 3. UI
- [ ] 3.1 Implement login, account shell, menu, expiry/logout/cache handling.
## 4. Acceptance
- [ ] 4.1 Run unit/architecture/contract/frontend/strict regression.
- [ ] 4.2 Run real PG fresh/upgrade/rollback/concurrency/isolation/expiry/revocation tests.
- [ ] 4.3 Run local Docker/API/browser tests and restart/dependency failure/resource checks.
## 5. Promotion
- [ ] 5.1 Bind evidence to exact source/image, review, merge step→phase→main.
- [ ] 5.2 Archive change, sync phase and update canonical ledgers/handoff without cloud deployment.
