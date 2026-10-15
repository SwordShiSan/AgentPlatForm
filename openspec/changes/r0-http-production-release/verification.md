# Verification
All current release items start NOT_RUN. Historical R0.4 PASS remains preparation only.

Normal:local Maven/DDD/Enforcer,Vue type/tests/build,contracts regeneration/invalid fixtures/links,nonroot SDK/Jackson/approval/crash/UNKNOWN,real PG/vector/Redis/MQ ACK-loss/duplicate/fence/S3/STS/restart,browser.
Production:merged Compose has only public80 and no443 or public middleware/API/metrics; designated domain/IP works,unknown Host rejects; dependency down reports NOT_READY and recovers; service restart succeeds.
Failure:wrong image/config/hash,insufficient disk/memory,missing XFS/wrong UUID/quota and unavailable dependency must fail closed. No missing-mount underlay writes. Driver failure restores exact legacy containers/restart policies and actual readiness; success retains suspended legacy.
Cloud:gVisor9/XFS guards,consistent backup and isolated restored PG/vector/state/objects/workspaces/keys/definitions/readiness; serial stacks. New uninterrupted>=1800s lightweight single-slot observation with memory/swap/disk samples. Sample extrema are not absolute capacity promises.
Delivery:exact-source protected PRs,immutable production tag,>=30day local/cloud evidence and hashes; original secrets/backups excluded from public Git. DNS/TLS NOT_APPLICABLE_BY_APPROVED_SCOPE,not PASS. Skill/doc HTTP/local-cloud policy verified; R1 stays NOT_STARTED.

## Local acceptance2026-10-15
Source05f1bc4:44 Maven tests0 failures,39 nonroot Linux SDK tests0 failures,real Java PG3 PASS;Vue15/type/build/frozen lock/contracts/links PASS. Local storage/vector/MQ ACK-loss/fence/S3/STS/restart PASS. Production config actual Docker/HTTP domain+IP/unknownHost404/sanitized Problem/single public port/internal services unpublished/bounded retries PASS. Read-only repeated config preparation preserves15 secret identities. Bash syntax PASS.

Historical failures retained in ignored evidence/r0-release:parallel dependency/browser injection changed API address while static gateway cached the old IP;serial old static configuration still failed502. Corrected production dynamic Docker upstream;4 browser cases PASS with only loopback Host added to the local fixture. Original production config Host boundaries tested independently and PASS. Chromium disallows overriding Host via extraHTTPHeaders;that attempted test-only change was removed. First standalone HTTP fixture had only an internal network,so publication was unreachable;using the same ingress+backplane layout as production passes. No artifact rebuild or public DNS change.

Cloud production startup/failure guards,serial recovery,public browser and fresh uninterrupted30min observation remain NOT_RUN. R0 remains unreleased until those gates and tag/delivery complete.
