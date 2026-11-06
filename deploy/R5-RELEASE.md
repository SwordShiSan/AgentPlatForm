# R5 release and operation

The release is authoritative only when `writes-open.json` and every preceding gate are PASS. Read `evidence/r54/production-release.json` for the final source, images and receipts; a locally VERIFIED stage alone is not a production release.

## Fixed entry

Use `python -X utf8 deploy/scripts/r5-release-client.py status` to query the original action. Preparation is `stage` then `refresh-scripts`; use `stage --new-attempt` only after a retained PASS restoration and an exact current-R4 inventory. This creates fresh rollback data rather than reusing an earlier window's snapshot.

Within an approved maintenance window, execute each action once and wait for its persisted PASS before the next:

`start → smoke → observe → backup-r5 → restore → rollback-drill → redeploy → final-smoke → final-observe → open-writes`

`start` backs up current R4, migrates to V21 and starts the complete 11-service R5 combination. Public writes remain closed. `smoke` journals every mutation and counts physical supplier requests; successful real acceptance stops further calls. Both observations last180seconds. Independent restore starts no worker/provider and verifies the same immutable objects and credentials. Unknown writes never get replayed.

Do not wait for extra checks between a successful final observation and `open-writes`: the maintenance watchdog is bounded. If an explicitly approved single continuation is necessary after watchdog recovery, `r5-finalize-window.py` first proves current R4 has no business changes, preserves data on refusal, restores the exact R5 snapshot, repeats only final readback/180-second observation and immediately opens the gateway. It requires all previous gates PASS and refuses a second continuation or any already-opened write intent. This is not permission to reset a watchdog indefinitely.

## Production topology

Keep the retained `ap.asset.v1` quorum queue and all messages. The versioned topology operator preserves queue types and existing roles, adds only exact asset resources and the extraction binding, and persists definitions. Production API/worker use prebuilt topology (`SPRING_RABBITMQ_DYNAMIC=false`) to avoid classic/quorum redeclaration conflicts. Release activation checks both asset consumers; manual ACK and outbox confirms remain unchanged.

## Resources and compatibility

Resident limits total2176MiB: API512, worker320, runner320, PG192, RabbitMQ256, Redis16, gateway32, SeaweedFS528. The512MiB renderer or sandbox,128MiB runtime allowance and64MiB helper bring the complete budget to2880MiB. Keep total≤2887MiB, host available≥768MiB and disk free≥5GiB. Cold-start capacities cannot be inferred from warm limit changes. No production deterministic provider or permanent Office service.

V21 is additive but V19 tombstones are not safe for the old file executor. During closed maintenance, rollback restores the matching upgrade-before R4 snapshot; never run R4 against R5 data. After durable `writes-open-intent.json`, old-snapshot rollback is forbidden: preserve newly accepted data and repair forward. Source XFS UUID stays unchanged; only an isolated restore copy gets a new UUID.

## Switching and evidence

The deployed platform-switch selector records the actual11-service R5 graph and exact images. Use the existing reviewed switching entry; do not assume a historical R2 service list. Legacy business containers/data remain stopped and retained. HTTP remains the approved entrance; no DNS change is required.

Secrets, SQL backups, account files, raw logs and journals stay in protected host directories. Public evidence contains bounded assertions and hashes. Final documentation and redacted evidence are packaged separately from Git, with two protected local copies and one cloud copy verified by SHA256 and retained≥30days. R6 requires a separate Plan/OpenSpec.
