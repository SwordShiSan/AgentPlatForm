# Verification

All implementation gates initially NOT_RUN. Normative validation is separate from feature evidence.

| Group | Assertions | Initial status |
|---|---|---|
| Helper | no-replace atomic file/tree move, delete isolation, exact cleanup, original-command query, crash windows, path/link rejection | NOT_RUN |
| Persistence | fresh/V18→19, active partial uniqueness, delete then recreate new UUID, scanner cannot revive tombstone, immutable origins, grants/content_revision | NOT_RUN |
| Real pipeline | PG/MQ/S3/runner versions/hash/quota, duplicate delivery, stale fence, response loss, known failures/UNKNOWN retained | NOT_RUN |
| Restore | file/tree new assets/versions, same logical identity, occupied destination rejects, quota not refunded/doubled | NOT_RUN |
| Evidence | forged path/JSON rejected, artifact old bytes survive move/delete, revoked rights denied, unrun checks not PASS | NOT_RUN |
| Browser | move/delete/history/restore/download, dirty buffer, refresh/conflicts,375/768/1440,200%,keyboard/themes | NOT_RUN |
| Engineering | Maven/DDD/Enforcer/Mapper, Vue tests/types/build, contracts regeneration/invalid fixtures, links/persistence guard/strict | NOT_RUN |
| Operations | independent fresh R52,180second sample, zero supplier calls, exact R4 restoration, acceptance stopped | NOT_RUN |

Use narrowly relevant R4 unchanged physical evidence, explicitly distinguish fixture/unit from real Linux. Never relabel skipped or failed tests. Preserve initial failures and original receipt identities. Final source/image/schema/hash/commands and protected promotion/delivery receipts are required for VERIFIED; not RELEASED.
