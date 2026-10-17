# R1 HTTP integration and release

Status: IMPLEMENTING; R1.4 is not VERIFIED and R1 is not RELEASED.

The single change is `r1-4-integration-release`; the canonical R1.4 plan governs all acceptance and release gates. GitHub Actions stay disabled. HTTPS remains reserved and unimplemented by explicit user decision, including subsequent stages. HTTP uses explicit `r1-http` configuration, HttpOnly/SameSite=Lax/non-Secure cookies and exact existing domain/IP Origins with CSRF. No DNS changes or checks.

`compose.r1-http.app.yaml` and `compose.r1-http.env.yaml` overlay the existing two cloud projects. The protected `deploy/r1-enabled` activation marker selects them in `production-stack.sh`; absence retains the R0 lifecycle. R1 starts API/worker/gateway and seven dependencies, never runner/egress/sandbox. Initial resident caps total2272MiB. Temporary operators/builds/restore stacks run serially. All active caps must remain within2887MiB with768MiB host reserve, no swap extension and root disk reserve5GiB.

Run additive Flyway V5 and reviewed `config/r1-runtime-grants.sql` before enabling identity/chat. V1–V4 and runtime schema1 remain unchanged. Default platform profiles remain closed. API may encrypt and insert credentials but cannot SELECT their ciphertext; worker decrypts on each authorized call. Independently generated32-byte master/cursor secrets stay protected outside Git/images. Only operations profiles provision accounts; no anonymous registration/default account.

Acceptance metering is cumulative and no longer hard-limited to20calls/1CNY. Preserve all existing UNKNOWN reservations; business budget accounts remain authoritative. Stop real acceptance when the provider reports exhausted balance/quota; minimize calls and do not rotate keys to evade limits. Official402 means insufficient balance;429 is rate limiting: https://api-docs.deepseek.com/quick_start/error_codes/ . No provider error bodies are logged in the deployed worker profile.

Local mandatory tests and exact-source step→phase→main promotion precede deployment. Transfer immutable accepted images with manifest/archive hashes; no cloud compilation. Save R0 config/images/policies and a consistent cold data/XFS/object/MQ/key backup. Do not import local test accounts/history. Provision one administrator and one ordinary cloud acceptance account with generated protected passwords and an encrypted platform credential.

Cloud gates include HTTP browser/auth/SSE/permissions, minimal real calls, >=30minutes task/dependency observation, independent serial consistent restore with actual quota/readiness/key verification, and R0 rollback followed by the same R1 candidate. Cloud failure restores R0 and keeps main; legacy business containers remain stopped. Create `production-r1-<sha>` only after every mandatory gate passes. Full checkpoint recovery is not delivered until R2.

Diagnostics: `GET /api/v1/tasks/{id}/diagnostics` returns owner-scoped code/classification/trace/action/partial-output facts; UNKNOWN never allows automatic replay. Request trace is server-generated and persisted through task/outbox/attempt/call; HTTP failures are correlated without logging bodies/secrets.

Final evidence and canonical-body delivery hashes are recorded separately at closeout. Local/cloud evidence retention >=30days. Historical failed candidates are retained, not rewritten as PASS.

## Master cold-start correction (release still pending)

The2272MiB candidate passed1800.14s and independent restore, but master64MiB was OOM-killed twice at final cold startup. R0 was restored12services; no R1 tag. R1 master is now128MiB with unchanged GOMEMLIMIT48MiB and official image; total2336MiB<=2887MiB. Existing real Flash/Pro results are reused exactly without new calls; a fresh30min window adds three master cold recreations, then repeats serial backup/restore/R0 rollback/final startup. Earlier passing scopes and failed candidate remain historical.
