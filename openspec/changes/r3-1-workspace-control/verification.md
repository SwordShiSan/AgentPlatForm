# R3.1 required gates

Plan approved2026-10-21. Baseline main93a87573ee43f1105fd77b91fd0b29b171068d6a; R2 released. Each result binds source, command/exit, assertions, image/config and fixture. Keep FAIL/NOT_RUN history; no paid supplier calls. Production switch evidence is separately retained in .cache/r31-switch-browser and host operations/platform-switch.

| Gate | Required assertions | Initial status |
|---|---|---|
| Framework | Real Nimbus signatures/alg/claims/body/response validation; docker-java socket/version/inspect and Jackson separation | NOT_RUN |
| Persistence | Fresh/V9-V10, old R2 compatibility, rollback/idempotency competition, one capacity reservation, scope/UUID/time mapping | NOT_RUN |
| Dispatch | Actual confirm return/loss, committed claim before ACK, duplicate delivery, two consumers, DB/MQ/runner outage, old lease/fence, no DB fallback | NOT_RUN |
| Runner | Tampered/expired/wrong aud/alg/replayed/forged receipt denied; real authorization/revoke; no API/worker socket; runner DB private-data denial | NOT_RUN |
| Resources | Native XFS independent byte/inode refusal,0700 safe cwd, mount guard, actual remount/hash, gVisor canary/resources and no runc fallback | NOT_RUN |
| Browser | Real API dual users/admin isolation/create/refresh/error/UNKNOWN/retry,375/768/1440,200%,keyboard/themes; no future buttons | NOT_RUN |
| Observation | >=1800s serial lifecycle/retry/fault/resource sampling, <=2887MiB and host768/free5GiB, actual R2 restored, no test resources running | NOT_RUN |
| Engineering | Maven/DDD/Enforcer/XML, frontend type/unit/build, generated no diff/invalid fixtures, links and strict | NOT_RUN |
| Closure | All forced gates PASS, two protected PRs, exact-source hashes, archive/ledgers/body+sanitized evidence delivery | NOT_RUN |
