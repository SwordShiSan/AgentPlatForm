Current release: **production-r4-57e1d081e2bb, R4 RELEASED**. App `b676b5f933dd6375fcef72628776f0007b8ec3db`, gateway `115f865a8434b98638a33f72c23c39092b43be8a`. Production observation, isolated restore, R3 rollback and same-candidate final smoke PASS. Historical failures are retained in the protected receipts and public index.

# R4 release and recovery

The release result is recorded in `r4-release-acceptance.json` only after production gates finish. Before the recorded release gate, production remains the retained R3 baseline. GitHub Actions remain disabled; protected PRs use exact-source local/native receipts. Cloud sandbox migration stays DEFERRED.

## Immutable inputs

Use the accepted application/gateway/helper config IDs and their individual source revisions, export manifest and archive SHA256. The gateway may retain an earlier accepted source revision. Do not relabel it as rebuilt from a later documentation merge. Build locally; import the same archive on the server. Preserve existing accounts, data, master/signing keys and the original XFS UUID. No test database or fixture account is imported into production.

`r4-release-stage.py` inventories the actual R3 graph and produces a private resolved R4 Compose file without stopping services. It verifies current mount/runtime and the 2880MiB total budget. API/worker/runner/gateway are480/336/336/32MiB, existing dependencies992MiB; sandbox512/runtime128/helper64 remain reserved. Keep at least768MiB host memory and5GiB disk free; no swap expansion. The complete accepted profile includes Plan, scoped permissions and workspace tools, with the existing HTTP Origin/CSRF/cookie rules.

## Ordered operations

1. Stage inputs with `r4-release-stage.py`. Verify exact scripts/grants and source container/config identities; save private action intents and PID/start-time receipts.
2. Run `r4-release-control.py start <folder>` in a detached, bounded host controller. It closes admission, drains ownership, cold-backs up R3 data/keys/MQ/XFS, migrates14→18, applies the nine restricted-role grants, and starts the same R4 candidate. Failure attempts exact R3 recovery.
3. Validate login/history, Plan/permissions, actual tool execution and immutable download/hash through the existing HTTP gateway. Credentials travel over the approved SSH tunnel. Count all real requests across acceptance environments, including unknown calls; never reset the stage meter or replay them.
4. Complete at least30minutes of mixed file/environment operations, dependency interruption and sampled resources. Do not label samples absolute peaks or a concurrency claim.
5. Run `r4-release-control.py backup-r4 <folder>` after closing writes. Run `r4-isolated-restore.py <folder>` serially with the source stack stopped. It verifies database fingerprints, credential decryption, object/workspace hashes, quota rejection, missing-mount refusal and readiness; no worker/model requests run in the restore project. Only the restored XFS copy receives a new UUID.
6. Run `r4-release-control.py rollback-drill <folder>`, verify original R3 login/history with the additive schema, then `r4-release-control.py redeploy <folder>` and final smoke on the same R4 images.
7. Record the production tag, update the platform-switch selection/snapshot to the actual R4 graph, and retain the R3 rollback containers/images and legacy data stopped. Stop acceptance and restore stacks, including fixture profiles.

Every action is single-intent: after a lost response read its existing receipt and exact process identity. Do not repeat an unknown deployment, release slot ownership merely on lease expiry, globally prune volumes, modify the source XFS UUID, or restart the legacy business platform as an implicit rollback. SIGTERM requests orderly restoration; raw credentials, SQL, signing material and detailed logs stay protected.

The four public tools access only the task-bound workspace. RISK_BASED asks for each shell call; FULL still respects live resource rights, Plan phase and isolation. Each shell stops the whole exact sandbox before result/version sealing. UNKNOWN remains explicit and is not a retry button. File-management edit/move/delete/restore UI and R5 remain unopened.
