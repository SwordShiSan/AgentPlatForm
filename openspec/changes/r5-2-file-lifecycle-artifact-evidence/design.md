# Design

## Baseline and implementation sequence
Baseline main4c80f52070325cad7d4d00a4f8ff50e92c6acc19, R5.1 VERIFIED. First validate atomic helper operations and original-command recovery. Then V19/contracts, typed persistence and scanner changes, signed runner/receipts, artifact facts, Vue and acceptance.

## Authority and transactions
FileMutationService coordinates FileMutationRepository with typed MyBatis Mapper/PO/XML and TransactionTemplate. Existing file_operation/outbox/inbox/command/receipt is the only execution authority. Keep quota→capacity→workspace→file lease→operation ordering and database-time owner/generation/fence/realtime authorization. Network/Docker/S3 waits are outside transactions. No business JDBC or shell-based mutations.

V19 adds deleted_at/deletion batch/retention, immutable mutation intent and manifest items, restore origin and immutable artifact/evidence references. Change full path uniqueness to active-path partial uniqueness and update ALL upload/capture/scan/path queries. New files at a deleted path get new UUIDs. Tombstones and immutable versions remain linked. Deletion changes content_revision, as do move/restore. Preserve minimum grants and schema1 runtime baseline.

## Physical mutation
Only current storage-READY workspace; no implicit environment preparation. Freeze at most1000 entries including root, versions, lengths/hashes, normalized source/target and manifest hash. Reject unsealed/changed facts, special files, links, self-descendant moves, existing destinations and more than1000 entries before any change. Target parent must exist. Move is atomic no-replace rename, retaining fileId/versionId.

Fixed versioned helper uses directory handles/no-follow paths and same-workspace reserved operation area. It refuses preexisting user collisions. Delete atomically quarantines the selected root; retain lease through durable receipt, projection commit and separate precise cleanup. Reserved content is excluded from indexing/tools. Unknown physical/stop/cleanup results retain lease and query original command, never blindly replay. Restore stages old immutable contents into a new tree then atomically publishes; it preserves logical IDs, creates new version/asset identities and records source origins. Existing asset/version FK forbids attaching an old asset to a new version. Stream copy and quota settlement reuse APPLY_UPLOAD children with explicit restore binding, not fake upload IDs.

## API and UI
GET files/{id}/versions; POST files/{id}/move; DELETE files/{id}; POST files/{id}/restore; GET workspaces/{id}/deleted-files; GET tasks/{id}/artifacts; GET artifacts/{id} and /download, all prefixed /api/v1. Mutations require CSRF/session/Origin/idempotency/If-Match. Move has targetParentId(null=root)/name. Restore selects exactly one sourceVersionId or deletionOperationId, optional targetParentId/name; directory restore uses deletion manifest. Responses202 refer to existing operation query. Missing version428, stale412, collision409, busy423. Stable pagination and request hashes include frozen identities.

ArtifactService validates actual task/operation/version/asset and size/hash using persistent joins, including workspace_write projections. Existing shell capture remains the source. Pin preserves bytes but never grants authorization. History/artifact downloads remain available after working-copy deletion to authorized members; revoked membership/resources deny access. Evidence PASS/FAIL/NOT_RUN is explicit; file existence is not test success. No general completion-repair loop in this stage.

Vue adds move/delete confirmations, history/source metadata, explicit restore, deleted-file listing and immutable artifact cards; preserve dirty buffers and distinguish historical read-only from latest edit. Query holds server facts. Complete recycle-bin/storage management and attachments remain R5.4; previews R5.3.

## Acceptance and operations
Isolated agentplatform-r52-local, fresh data/credentials. Reuse parameterized verified R51 startup patterns, including complete grants/MQ topology/bucket bootstrap. Budget2880MiB initially, max2887MiB, host reserve768MiB, root reserve5GiB, no swap expansion. Necessary Linux window pauses R4 only after prerequisite checks, bounded watchdog and exact restoration. Final observation180seconds (max300 only on growth suspicion); no repeated unchanged recovery/isolation matrix. No supplier calls or production tag. Protected step→phase→main, archive, ledgers, Skill and two protected hash-verified delivery copies.
