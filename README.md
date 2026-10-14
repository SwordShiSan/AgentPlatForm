# AgentPlatForm

R0.4安全修订实施中：Java21/AgentScope2.0.4保持，Boot4.0.8/Tomcat11.0.25/Jackson2.21.7与3.1.7/Vitest4.1.11已更新，schema1不变。HOST44/Vue15及契约构建通过，整镜像安全审计仍FAIL、云端完整重验NOT_RUN；不标VERIFIED/RELEASED、不进入R1。详见[审计记录](deploy/AUDIT.md)，下文按阶段保留历史。

新一代云端 Agent 平台独立 Git 单仓。R0.1工程、契约生成与健康壳层已通过本地Docker/CI并经PR晋级main；**尚无业务智能体功能、未部署生产**。实时阶段状态以外部局部台账为准。

R0.2默认关闭的SDK适配及离线框架探针已通过本地/CI并经PR晋级，公开页面仍为健康壳层。脚本模型/loopback协议不等于真实供应商。R0.3真实存储、云端最低资源与gVisor/XFS基线已通过全部云端门禁和PR10/11晋级，状态以局部台账和[归档验收记录](openspec/changes/archive/2026-10-13-r0-3-storage-deploy-baseline/verification.md)为准；生产发布仍待R0.4。

## 方案入口

- [中间件选型与可靠消息](../项目文档/06-AgentScope重构专项/00-总体方案/07-中间件选型与可靠消息架构.md)
- [用户数据归属与存储契约](../项目文档/06-AgentScope重构专项/01-统一契约/07-用户数据归属与存储契约.md)
- [用户资产管理与交互](../项目文档/06-AgentScope重构专项/05-平台治理/06-用户空间与持久化资产管理.md)

- [重构专项总导航](../项目文档/06-AgentScope重构专项/00-总体方案/00-文档导航与阅读顺序.md)
- [统一契约](../项目文档/06-AgentScope重构专项/01-统一契约/00-契约索引与字段字典.md)
- [R0–R9 连续路线](../项目文档/06-AgentScope重构专项/07-交付与验收/01-连续迭代路线.md)
- [测试矩阵](../项目文档/06-AgentScope重构专项/07-交付与验收/02-测试矩阵与阶段门禁.md)

正文只维护在上述专项目录。本仓不复制方案；交接/克隆时必须同时携带该目录并保持同级布局。远端为 [SwordShiSan/AgentPlatForm](https://github.com/SwordShiSan/AgentPlatForm)，主分支 main。方案正文和进度台账不在本仓Git历史中。

## 实施进度入口

- [全局重构进度](../项目文档/06-AgentScope重构专项/08-重构进度管控/01-全局重构进度.md)
- [模块与功能追踪](../项目文档/06-AgentScope重构专项/08-重构进度管控/02-模块与功能追踪.md)
- [进度维护规则](../项目文档/06-AgentScope重构专项/08-重构进度管控/README.md)

初始化基线：30模块、90功能点、R0–R9共40个小阶段；业务实现均未开始。这里仅为2026-10-12快照，实时状态只维护在上述台账。GitHub网页无法打开仓库外相对链接；交接时必须同时提供唯一专项目录，不以克隆成功代替文档交付。

## 工程布局
frontend 为 Vue3/TypeScript；backend 为 Java21 六模块 DDD；contracts 为机器契约；openspec 为规格与变更记录；deploy 为本地容器构建与验收。构建及运行命令见各目录README。R0.1只交付健康壳层；AgentScope运行实验、完整中间件和契约冻结分别属于R0.2–R0.4，全部通过并发布后才能进入R1。

R0.3采用云端Linux开发验收替代本机Docker必测；现有4核/3655MiB/40GiB云主机、单执行槽/单活动gVisor sandbox、独立XFS镜像project quota，旧服务仅在获准窗口可逆暂停。详见[部署入口](deploy/README.md)和外部[正式计划](../项目文档/06-AgentScope重构专项/07-交付与验收/阶段/R0.3-存储部署与最小云资源实施计划.md)。
