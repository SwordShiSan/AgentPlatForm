# Deployment

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

R0.3扩展agentplatform-env与应用profiles。每个R大阶段均按同一镜像digest完成本地canary和云端验收；小阶段通过后经两PR晋级main。生产tag只在大阶段云端通过后创建。设计见[部署方案](../../项目文档/06-AgentScope重构专项/07-交付与验收/03-本地与云端部署.md)。不复用旧库/卷/入口；新站TLS和部署目标须明确，旧HTTP80入口保持原样。
