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

## Implementation checkpoint, not final acceptance

- R2 switch: PASS; host operation `20261021T084817Z`, ten original R2 containers running, ten legacy stopped; public login/history/dual-context/logout browser PASS, supplier calls0. Host-aware readiness correction and original failed assumption retained. Production tag unchanged.
- Initial engineering: Maven verify, frontend type/unit/build, generated contracts/invalid fixtures/links and strict PASS. Evidence logs `r31-verify-1792576736468530000.log` and associated frontend logs. Subsequent code and final candidate must be rerun; this is not a frozen-source acceptance.
- Targeted actual PostgreSQL: fresh install/V9→V10 and baseline1, transaction rollback, idempotency/capacity, scope/admin/revocation, fence/lease, exact receipt and UNKNOWN retention PASS. Real MQ confirm-loss/duplicate/two-consumer and durable handoff before connection/ACK loss PASS. Effective `ap_runner` grants reject credentials, chat, private SDK state and fence modification. Latest successful targeted run `r31-pg-1792577783881581500.log`; expiry sweep retains owner/fence/reservation. One failed expiry fixture queried outbox again during its publish lease; original message reuse corrected the fixture without changing safety assertions.
- Nimbus6 tests and real runner HTTP3 tests PASS, including separate response identity, wrong issuer/audience/algorithm/time, body/path/fence, replay, missing token and bounded request rejection. Bounded response/total deadline and profile isolation5 tests PASS (`r31-selected-1792578658560068100.log`).
- Non-root Docker Engine probe PASS: docker-java3.7.1/HttpClient5, Unix socket, Engine28.5.1/API1.51 (requested1.45), actual fixture inspect, Jackson2/3. Desktop image-store/index identity is distinct from exported config identity and will be resolved by the final export chain.
- Local real API/browser PASS in `.cache/r31-workspace-browser-1792578809469/result.json`: three separate accounts, intentionally lost create response with stable input/key and one workspace, detail refresh, user/admin isolation, CSRF, definite-failure retry,375/768/1440, keyboard/light/dark/CSS200%, internal route denial and logout. This exercised two independent worker containers and the real signed runner/helper path. It proves missing-XFS refusal, **not** positive XFS allocation or sandbox execution. UI button/component changes and later final images must be rechecked.
- Native quota/helper: preliminary immutable helper built and no-mount refusal PASS; boundary tests3 PASS. Actual XFS allocation/quota/remount and gVisor are NOT_RUN. Desktop has no configured runsc; cloud read-only admission showed R2 readiness200, unchanged10 running identities, existing prjquota/runsc and14,469,320,704 root free bytes. No maintenance started, no R3 production deployment.
- Integration failures retained: nested configuration scanned into migration (fixed explicit profiles/feature condition plus isolation tests); local internal-only Docker network accepted configured ports but exposed none (fixed separate local-access bridge for gateway/data debug ports; runner/API/worker internal-only). Windows restricted loopback/temp failures passed under authorized host execution. Build context whitelist and official Debian HTTPS mirror correction retained. No third-party source rebuild, credential output or model call.

Mandatory final engineering, full fault/browser matrix, positive native quota/gVisor,>=1800s observation, exact-source artifacts, protected promotions and closure remain NOT_RUN/PENDING. R3.1 is IMPLEMENTING, not VERIFIED.
