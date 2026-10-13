## Prerequisites and operations
- [x] 1. Record formal approved Plan, update ADR/deployment/R0/R3/R9/AGENTS and validate OpenSpec before implementation.
- [x] 2. Snapshot cloud resource/image/mount/policy inventory, verify backup, save diagnostic sample and precisely clean obsolete images/logs; packages without proven unused status remain preserved.
- [x] 3. Implement reversible pause/restore; prove old current/rollback images and data remain recoverable.
## Storage and application
- [x] 4. Pin dependency/image versions; add two least-privilege Compose projects, secret templates, migration job and role profiles.
- [x] 5. Implement independent schemas and scoped PostgreSQL CAS; verify rollback/concurrency/schema rejection and Jackson2/3 round trips.
- [x] 6. Add real RabbitMQ confirm/duplicate handoff/fence and disconnect/restart probes; manual ACK only after DB commit.
- [x] 7. Add private S3 PUT/GET/HEAD/Range/multipart/hash/restart probes and Redis loss/recovery probe.
- [x] 8. Implement public readiness and internal observations; regenerate contract client and verify dependency failure HTTP behavior.
## Sandbox and UI
- [x] 9. Install/version-pin gVisor without default runtime changes and provision/verify4GiB XFS image mount/quota.
- [x] 10. Implement/test one-slot admission and trusted host acceptance fixtures for limits, selected mounts, networking denial, idle cleanup and fail-closed runtime/quota capability; production command/approval integration remains R3-gated.
- [x] 11. Implement tokens/responsive shell/error routes; verify375/768/1440, dark, keyboard,200%zoom and real API recovery.
## Acceptance and promotion
- [x] 12. Run R0.2 host/Linux regression and all real R0.3 probes plus permission scan, failures recorded per case.
- [x] 13. Run30-minute lightweight sequence/dependency restarts, collect sampled memory/swap/CPU/disk, prove platform survives sandbox exhaustion.
- [x] 14. Exercise legacy→new→legacy rollback, restore original policies; keep new business shell disabled.
- [x] 15. Review/secrets scan; PR10 step→phase and PR11 phase→main with strict real-storage CI passed; main adde323 synced to phase; update local/global/risk/evidence ledgers. Completed change archive/navigation closeout follows document PRs.
