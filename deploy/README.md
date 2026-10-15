# Current R0.4 acceptance policy (2026-10-15)

Local Docker builds and local/cloud functional acceptance replace GitHub builds. Official middleware identities live in runtime-images.lock.json (stable R0.3 baseline). Use the official gVisor20261005.0 package. Source rebuild candidate Dockerfiles and foundation security patches below are historical, not required startup dependencies. Unrelated third-party scan findings are informational, never reported as fixed. App/SDK/contracts/isolation/quota/restore gates remain mandatory. GitHub Actions workflow is archived; PR promotion uses exact-source local/cloud evidence. R0 remains unreleased.

# Deployment

## Historical R0.4 foundation candidate preparation (superseded)

The following source-rebuild/CI instructions are retained as history and are not the current startup path. Current local acceptance uses `scripts/local-acceptance.py`; official middleware identities are in `runtime-images.lock.json`, and cloud import uses verified config IDs. Do not invoke paused foundation rebuilds for this acceptance.

Current approved inputs are pinned in `foundation.lock.json`; run `python3 deploy/scripts/foundation-inputs.py --fetch` before any build. Offline signed Alpine packages and exact source/module hashes reject drift. PostgreSQL/vector, Redis and SeaweedFS candidates use dedicated Dockerfiles and disclose rebuilds. Redis7.4 uses RSALv2/SSPLv1; the actual upstream license is retained in the image. Distribution must retain license notices and requires review of the selected license's source/service restrictions; no BSD claim or license waiver.

Compose environment images must be supplied by validated `images.env` (cloud config IDs), or by the CI fixture variables. There is no mutable production default. CI builds and audits locally, exporting only accepted images; failed or incomplete scans retain evidence and block export. New foundations remain candidates until fresh runtime, quota,>=30-minute observation and serial restore gates pass. R0.4 remains IMPLEMENTING and R0 remains unreleased.

## R0.3 minimal cloud developer acceptance

This step uses the explicitly authorized cloud Linux developer environment, independent of legacy data and the later production release gate. Budgets, architecture decisions and all case evidence are defined in the external [formal plan](../../项目文档/06-AgentScope重构专项/07-交付与验收/阶段/R0.3-存储部署与最小云资源实施计划.md) and [step verification](../openspec/changes/archive/2026-10-13-r0-3-storage-deploy-baseline/verification.md).

- `compose.env.yaml`: independent PostgreSQL17/pgvector, Redis7.4, RabbitMQ4.3.6 and SeaweedFS4.48; no published ports, per-role credentials, persisted filer metadata, actual Leader/HTTP health ordering, bounded RAM/logging.
- `compose.app.yaml`: the same Java artifact provides api/worker and minimal non-Web runner/egress profiles. They do not enable R3 command/proxy business capabilities. Only API connects public gateway; metrics are on internal8081. Production socket/mTLS/fence command wiring remains R3-gated.
- Trusted `deploy/scripts/` operations default to dry-run where destructive. Inventory and SQL backup precede exact image/log cleanup; referenced legacy volumes and current/rollback images are retained. Pause saves original restart policies, suppresses always during Docker restart, and restore validates original identities/states/policies.
- `sandbox-host.sh`: pinned complete gVisor20261005.0 release with sibling binaries, explicit systrap runtime without changing Docker default, and a preallocated4GiB XFS image with UUID/prjquota verification. Project quota returns ENOSPC on this Linux6.6 kernel; prove project limits and global free space rather than assuming EDQUOT. Shell stays disabled unless all required gates pass.
- Probe images/host fixtures test actual SDK/JVM, DB rollback/CAS/version rejection, dropped AMQP Basic.Ack/duplicate/fence, private S3 multipart/hash/Range/restart, Redis, gVisor limits and30-minute minimal-host observations. These are acceptance fixtures, not the production R2 scheduler or R3 runner API.

Build and verify on the workstation/CI. Transfer archives with SHA256, read archive `manifest.json` config IDs and compare with imported Linux IDs: DockerDesktop indexes and `RepoDigests` are not portable through save/load. `verify-imported-images.py` emits a protected operator `images.env`; Compose uses immutable imported config IDs. No password appears in `.env.example`, source or logs. `prepare-cloud-config.py` writes only protected cloud files.

Inside the authorized window, upload reviewed scripts/configs and verified images under `/srv/agentplatform`; syntax-check scripts and Compose before `cloud-window-start.sh`. Startup failure stops only new projects and restores legacy. After successful developer tests, `cloud-acceptance.sh stop` then `legacy-window.sh restore`; verify the old virtual-host HTTP entry and backend readiness. Do not run global prune/down-v or remove business data.

Observe one execution slot/one on-demand sandbox512MiB (plus128MiB runtime allowance), CPU1/PIDs128/tmpfs64MiB, no swap expansion/network/socket/whole-home mount. The idle60s, quota, resource and recovery evidence is separate from a capacity promise. Full observability deployment is optional/later; current baseline provides internal health/metrics and bounded logs. R0 is not RELEASED until R0.4 and the distinct production gate.

## R0.2 offline framework probes

先在backend运行Wrapper verify，然后运行 `./mvnw -B -ntp package dependency:copy-dependencies -DskipTests -DincludeScope=test -DoutputDirectory=target/probe-libs`，Windows使用mvnw.cmd。编译测试类和依赖进入固定JDK21镜像，运行期无网络、非root、只读根目录、无socket/用户卷，测试临时文件只写/tmp，报告使用专用probe-reports卷。

```sh
docker compose -p agentplatform-app -f deploy/compose.r0.1.yaml --profile framework-probe build framework-probe
docker compose -p agentplatform-app -f deploy/compose.r0.1.yaml --profile framework-probe run --name agentplatform-r02-probe framework-probe
docker cp agentplatform-r02-probe:/reports evidence/r0.2/linux-reports
docker rm agentplatform-r02-probe
```

不修改旧栈，不运行global prune或down -v。报告卷保留用于复核；仅重复本探针时可覆盖其JUnit报告。单次探针容器按精确名称管理，不挂用户工作区。真实模型、PG/MQ/S3、runner/XFS和云端验收不在此profile中。

R0.1 [Compose](compose.r0.1.yaml) 只启动固定构建产物的api和gateway，属于本地应用壳层，不是R0.3完整中间件/runner基线。先在backend执行Wrapper verify，在frontend执行锁文件安装、契约校验、类型/测试/build，再从仓库根目录运行：

```sh
docker compose -p agentplatform-app -f deploy/compose.r0.1.yaml config --quiet
docker compose -p agentplatform-app -f deploy/compose.r0.1.yaml up -d --build --wait --wait-timeout 120
```

入口 http://127.0.0.1:18080 ，健康 GET /api/v1/health；仅loopback公开，API无宿主端口。两容器非root、只读根目录、无能力/socket挂载、限定资源、restart:no；基镜像按真实digest固定。IMAGE_TAG/GATEWAY_PORT可显式覆盖。Compose stop/start api是恢复验收动作，不触及旧栈。

停止仅运行 `docker compose -p agentplatform-app -f deploy/compose.r0.1.yaml down`，不加-v、不执行全局prune。

R0.3扩展agentplatform-env与应用profiles，开发canary采用已授权云端Linux，同一固定镜像另行通过生产门禁；小阶段通过后经两PR晋级main。生产tag只在大阶段云端通过后创建。设计见[部署方案](../../项目文档/06-AgentScope重构专项/07-交付与验收/03-本地与云端部署.md)。不复用旧库/卷/入口；新站TLS和部署目标须明确，旧HTTP80入口及数据保留，服务可在获准窗口暂停并恢复。
