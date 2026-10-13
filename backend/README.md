# Backend

R0.1建立Java21/Boot4.0.4/Wrapper3.9.11六模块健康壳层；R0.2增加默认关闭的AgentScope2.0.4适配器、HC-1端口和真实SDK/进程恢复探针，无公开任务API或真实供应商接入。生产存储、身份与任务协调仍属后续阶段。

依赖：types为基础；domain→types；api→types；infrastructure→domain/types；trigger→api/domain/types；app→trigger/infrastructure/domain/api/types。用例接口放api，app实现，trigger只依赖接口，避免app/trigger循环。只有app负责启动装配和事务协调；仅infrastructure接入AgentScope。

在本目录运行 `./mvnw -B -ntp verify`（Windows：`./mvnw.cmd -B -ntp verify`）。构建后运行 `java -jar platform-app/target/platform-app-0.1.0-SNAPSHOT.jar`，健康端点 `GET /api/v1/health`。默认端口8080，无数据库依赖；Flyway迁移准备不等于R0.3存储基线。不要连接旧库。
