## Governance
- [ ] 1.1 Record approved HTTP/no-DNS plan and ADR; complete five artifacts and strict before implementation.
## Implementation and local gates
- [ ] 2.1 Add HTTP production overrides,Host routing and guarded lifecycle/rollback.
- [ ] 2.2 Run full engineering/SDK/storage/browser regression and new production boundary/failure tests.
- [ ] 2.3 Review immutable identities and promote exact configuration step→phase→main.
## Cloud release
- [ ] 3.1 Fresh inventory/backup/capacity admission; pause original legacy stack.
- [ ] 3.2 Run real storage/runtime/quota/serial backup-restore gates on final candidate.
- [ ] 3.3 Publish HTTP80,verify IP/Host/browser and controlled service restart.
- [ ] 3.4 Fresh>=30min observation PASS; retain deployed healthy new stack and stopped old stack.
## Delivery
- [ ] 4.1 Verify production tag and protected local/cloud source/config/image/evidence hashes.
- [ ] 4.2 Update canonical docs/ledgers and existing skill; retain HTTP/no-DNS/no-hosted-build rules.
- [ ] 4.3 Archive/promote records; R0 RELEASED,4/40 VERIFIED,1/10 RELEASED,R1 NOT_STARTED.
