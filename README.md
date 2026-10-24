# AgentPlatForm

当前实施：**R3.3 IMPLEMENTING，R3已完成2/4 VERIFIED、未RELEASED**。前序[R3.2验收](deploy/r3.2-acceptance.json)已完成；本期按[正式计划](../项目文档/06-AgentScope重构专项/07-交付与验收/阶段/R3.3-文件资产与额度实施计划.md)和唯一change `r3-3-file-assets-quota` 实现公开文件、显式替换、不可变版本与额度。首批工程和传输探针通过不等于完整阶段通过；文件功能开关默认关闭，签名runner/MQ、浏览器、Linux故障及最终观察未全部完成前不晋级。云端继续R2，不调用真实供应商。以下为历史阶段快照，实施状态以仓库外局部台账为准。

R3.3 的固定本机回归入口、已验证子集及待验收边界见[本地实施说明](deploy/R3.3-LOCAL.md)。

当前实施：**R3.2 IMPLEMENTING，R3仍1/4 VERIFIED、未RELEASED**。按批准计划开始沙箱环境与受保护数据面；公开文件管理和任意shell仍关闭，生产继续R2。唯一change `r3-2-sandbox-data-plane` 已strict通过，所有本阶段实际验收仍NOT_RUN。

当前：**R3.1 VERIFIED，R3 完成 1/4、未 RELEASED**。工作区控制面、独立签名 runner、真实 XFS/gVisor 与故障恢复验收已通过 PR #78/#79 两级晋级。READY 仅存储目录和配额，执行环境仍为 NOT_CREATED，shell/文件能力关闭。云端继续原 R2 十服务，旧平台停机保留；没有 R3 生产部署或真实供应商调用。[验收摘要](deploy/r3.1-acceptance.json)、[实施与运维边界](deploy/R3.1-LOCAL.md)。R3.2 须另行 Plan/OpenSpec。以下为历史过程。

当前：**R2.4 VERIFIED，R2 4/4 VERIFIED、RELEASED**。生产标签 `production-r2-9ee7aadfe242`；[打开聊天平台](http://124.222.55.140/)、[云端发行验收](deploy/r2-release-acceptance.json)、[发行与恢复说明](deploy/R2-RELEASE.md)。云端观察、独立一致恢复、R1回滚及同一R2重启全部通过；R3尚未启动。以下早期阶段结论保留为历史记录。


当前实施：**R2.4 VERIFIED，R2完成4/4 VERIFIED、未 RELEASED**。[checkpoint续跑本地验收](deploy/R2.4-LOCAL.md)、[脱敏证据](deploy/r2.4-acceptance.json)及[R2发行边界](deploy/R2-RELEASE.md)。本地fixture已停止、数据/镜像保留；本阶段未变更云端，SSH连接问题仍待排查，当前公网健康未核验。OpenSpec保持活动，云端门禁通过前不建R2生产tag、不进入R3。以下保留已验收基线和历史记录。

当前开发：**R2.3 VERIFIED，R2完成3/4、未 RELEASED**。取消收敛、停止回执、未知调用只读投影及受保护运维对账已通过本地强制门禁和两级PR；[本地说明](deploy/R2.3-LOCAL.md)、[脱敏证据](deploy/r2.3-acceptance.json)。云端R1未改，真实供应商调用0；R2.4另行Plan/OpenSpec。

当前开发：**R2.2 VERIFIED，R2完成2/4、未 RELEASED**。一致快照、可靠事件续读及30天受控前缀清理已通过本地验收和两级PR；[本地说明](deploy/R2.2-LOCAL.md)、[脱敏证据](deploy/r2.2-acceptance.json)。云端继续已发行R1，真实供应商调用0；R2.3另行Plan/OpenSpec。

当前：**R1.4 VERIFIED，R1 4/4 VERIFIED，R1 RELEASED**。云端HTTP已开放身份、个人空间、普通聊天、配置及密钥管理；[访问平台](http://124.222.55.140)。生产tag `production-r1-24b3c6558d17`。当前及后续HTTP、HTTPS保留暂不实现，不检查/修改DNS。R2.1 VERIFIED（本地执行所有权与账本，R2完成1/4、未RELEASED）；详见[R1发行与恢复记录](deploy/R1-RELEASE.md)及[脱敏验收摘要](deploy/r1.4-acceptance.json)。以下为历史阶段记录。

**R0 已正式 RELEASED，R1.1 已本地 VERIFIED，R1.2 已本地 VERIFIED，R1 完成2/4、未 RELEASED**。HTTP入口：[公网IP](http://124.222.55.140)，域名Host `agentplatform.lailihao.cloud` 已配置；按用户决定不配置HTTPS、不检查或修改DNS。云端仍运行R0，生产tag `production-r0-c46a73ae4d00`，旧栈保留停机；本次不部署身份或聊天。R1.1通过本地Docker/真实PG/浏览器及PR22/23；R1.2已通过PR26/27晋级，默认关闭；云端不部署本阶段功能。详见[R1.2独立本地环境](deploy/R1.2-LOCAL.md)、[R1.1本地验收](deploy/R1.1-IDENTITY.md)及[R0发行记录](deploy/R0-RELEASE.md)。以下阶段记录保留历史。

新一代云端 Agent 平台独立 Git 单仓。R0.1工程、契约生成与健康壳层已通过本地Docker/CI并经PR晋级main；**尚无业务智能体功能、未部署生产**。实时阶段状态以外部局部台账为准。

R0.2默认关闭的SDK适配及离线框架探针已通过本地/CI并经PR晋级，公开页面仍为健康壳层。脚本模型/loopback协议不等于真实供应商。R0.3真实存储、云端最低资源与gVisor/XFS基线已通过全部云端门禁和PR10/11晋级，状态以局部台账和[归档验收记录](openspec/changes/archive/2026-10-13-r0-3-storage-deploy-baseline/verification.md)为准；生产发布仍待R0.4。

## 方案入口

- [R2.1 独立本地环境与账本说明](deploy/R2.1-LOCAL.md)

- 首页入口修复已上线：`/` 登录后直接进入聊天，`/status` 保留连接检查。[脱敏验收摘要](deploy/r1-home-entry-acceptance.json)。
- [R1 首页聊天入口修复计划](../项目文档/06-AgentScope重构专项/07-交付与验收/阶段/R1-首页聊天入口修复实施计划.md)
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

### R1.3 本地验收
聊天草稿、模型/凭据配置快照与个人key管理的当前验收记录见[本地说明](deploy/R1.3-LOCAL.md)和[脱敏摘要](deploy/r1.3-acceptance.json)。R1尚未发行，云端保持R0；正文和保护证据另行本地交付，不随Git推送自动同步。

## 2026-10-18 框架纠正与后续实现入口

- [源码评估与框架责任矩阵](../项目文档/06-AgentScope重构专项/00-总体方案/08-框架复用与平台完整性专项评估.md)
- [R2.C框架化纠正计划](../项目文档/06-AgentScope重构专项/07-交付与验收/阶段/R2.C-框架化与平台架构纠正计划.md)
- [R2–R9技术架构与实现约束](../项目文档/06-AgentScope重构专项/07-交付与验收/07-后续阶段技术架构与实现约束.md)

R2.C框架纠正VERIFIED，实现已通过[PR50](https://github.com/SwordShiSan/AgentPlatForm/pull/50)／[PR51](https://github.com/SwordShiSan/AgentPlatForm/pull/51)两级晋级并归档；[验收收据](deploy/r2.c-acceptance.json)、[本地复验](deploy/R2.C-LOCAL.md)、[SDK复用结论](deploy/r2.c-sdk-reuse.json)。R2仍1/4、未RELEASED，云端R1不变；R2.2另行Plan/OpenSpec。正文在仓库外，Git推送不代表正文同步。
