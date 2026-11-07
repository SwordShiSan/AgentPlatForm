## Acceptance

状态 NOT_RUN。绑定源码、迁移、依赖和镜像；真实供应商调用0。

## 阶段内记录

- 能力绑定纯领域测试 2 项 PASS。Skill ZIP 校验的定向测试 6 项 PASS，包含链接、Unix 扩展链接及文件数量边界；导入持久链路已实现，真实 PG／MQ／S3 端到端仍未验收，不能视为完整包安全验收。
- 基于隔离 PostgreSQL 的 V22→V23、连接器发现版本启用／停用、幂等及跨用户拒绝定向回归 PASS（源码工作树内容绑定在本地验收记录）。真实出口、远程服务及界面仍须分别验收。
- `McpProbeExecutionTest` 验证探测本地期限到达后记录 UNKNOWN、原调用未退出时不释放消费者、迟到结果不发布，定向 PASS；这不是远端调用停止证明，也不替代真实代理和连接异常验收。
- 固定 SDK 字节码核验发现其响应聚合早于平台 schema 大小校验；不能将事后 schema 检查称为接收前限流。生产容器资源限制和完整密钥泄漏扫描仍 NOT_RUN。
- 子进程定向检查：本机授权执行下，实际 SDK 通过回环代理拒绝直连，超时与超限输出的测试子进程确认退出后才释放位置，PASS；未经授权的执行入口曾因 Windows 回环访问受限而 FAIL，已在同一输入的授权入口复验。打包后的实际 JAR 通过回环代理启动探针，JAR SHA256 `77e46b9128e61dacda6ad751da12ab701d4938d8ada5e328b93ea42f072c4579`。
- 实际 SDK／临时 TLS 夹具的大响应实验：96MiB 响应已发送约6MiB后，64MiB 测试堆下子进程返回503并正常退出，未发布工具；初始假设必须由 JVM 内存退出，属错误断言并保留失败记录。该实验不证明生产容器资源预算、Squid ACL或业务调用；这些仍 NOT_RUN。
- 打包 SDK 经实际 Squid 及隔离 TLS 夹具发现固定工具，Bearer 测试凭据不出现在进程输出；另一测试域名解析到回环地址时 Squid 返回403，PASS，证据 `.cache/r62-local/squid-sdk/run-1794042333947840900`。夹具只允许两个固定测试域名，不代表 DNS 重绑定、所有特殊地址、生产出口配置或受保护业务调用通过。
- 更新后的打包程序经实际父进程、受限子进程、Squid 和 TLS 夹具发现工具，回环目标被拒绝，PASS；产物 SHA256 `25b91be2e050b020567f716487e65e7f911d0d21bb997334dbf239171fb9fb77`，证据 `.cache/r62-local/squid-sdk/run-1794043402074834100`。生产容器的总资源预算和业务调用仍未验收。
- 独立 PG／RabbitMQ／SeaweedFS 联合 Skill 导入实验：通过实际对象上传、confirm／领取／ACK、归档校验和 DRAFT 入库，PASS；证据 `.cache/r62-local/integrated/run-1794038456173729200` 与相应 PostgreSQL 输入哈希记录。该次发送了重复消息但未证明其已到达 inbox，重复投递断言需重新验证。首次尝试因测试对象存储默认卷增长耗尽测试卷失败，保留 `.cache/r62-local/integrated/run-1794038136929286700`；按已有低配卷增长经验修正夹具后通过。此结果不覆盖真实 API／浏览器、所有故障窗口或 MCP。
- 加入真实 inbox 到达确认后重跑：第二条消息确实进入持久领取检查且未再次执行，PG／MQ／S3 闭环 PASS，证据 `.cache/r62-local/integrated/run-1794038701480273500`。该实验使用独立回环依赖和实际存储适配器，不代表生产受控出口或 runner 验收。
- 扩展同一 PG／MQ／S3 联合夹具：导入发布后，按数据库保存的文件清单从私有对象存储流式暂存并核对哈希；跨用户元数据读取拒绝，撤销版本后再次暂存拒绝，PASS。证据 `.cache/r62-local/integrated/run-1794044707383056100`。授权检查由测试夹具调用现有版本服务，不代表 runner 已接入或任务绑定已完成。
- 官方维护的 `ubuntu/squid:7.2-26.04_beta` 查询未找到；`7.2-26.04_edge` 的 linux/amd64 manifest 已固定并拉取，无网络容器中执行二进制报告 7.7，而 tag／OCI label 报告 7.2。实验身份见 `deploy/config/r62-squid-image.json`；受控出口功能、DNS／实际目标验证仍 NOT_RUN。
- `AgentScopeMcpCompatibilityTest` 在独立回环 Tomcat 使用官方 SDK Streamable HTTP 服务，发现工具时调用计数为0，显式调用后计数为1，PASS；该探针是明文回环，仅证明 SDK 兼容，不代表 TLS、受控出口或平台账本通过。测试停止时 Tomcat 对 Reactor 共享线程发出清理提示，退出码0；持续资源释放仍需后续受限环境观察。
- SDK 可用性对照增加不可达代理检查：配置的 HTTP 客户端自定义器被调用，代理不可用时初始化失败而不直连，之后正常回环发现与调用仍 PASS；这不替代真实代理／TLS 联合实验。
- `McpGuardInteropTest` 使用真实 SDK MCP 服务及受控 Harness：发现不触发调用，策略检查后先把原操作推进 RUNNING，再执行一次 MCP 调用并提交 SUCCEEDED；拒绝策略下操作为 DENIED 且不触达 MCP 服务，PASS。账本使用独立文件测试夹具，不等于 PostgreSQL、MQ、审批恢复或生产连接器验收。
- `ApprovalPostgresTest#r62ProtectedMcpFixtureRunsOnlyAfterOriginalApproval` 在独立 PostgreSQL、真实 Harness、原操作批准及 SDK 回环服务下 PASS：批准前远端调用0次，批准后1次，审批消费及工具成功事实各1条。输入哈希和日志 `.cache/r62-local/postgres/run-1794046378558932900`；此测试使用固定测试工具及明文回环，不覆盖受控出口、生产连接器版本、真实 MQ 或 UNKNOWN 故障。
- 扩展同一测试使远端收到并执行后延迟响应，客户端超时；对应任务 BLOCKED、原操作 UNKNOWN、远端只收到一次且恢复不可用，PASS，日志及输入哈希 `.cache/r62-local/postgres/run-1794046603742756800`。这仍是回环测试，不证明生产连接器、受控出口、真实 MQ 或远端副作用已停止。
- 出口夹具首次使用64MiB时容器启动即 OOMKilled、退出137；独立诊断记录保留于 `.cache/r62-egress/887aade38e3d`。拟在总预算内按实际需要调整限额，不能将此失败记为通过。
- 将容器限额提高至128MiB后仍在启动时 OOMKilled，记录见 `.cache/r62-egress/94795279a6dc`；进一步以显式受限缓存配置验证，原因尚待实证。
- 限制文件描述符至1024并设置8MiB缓存后，128MiB容器正常启动，日志见 `.cache/r62-egress/de2b86b1991e`；代理拒绝测试驱动最初读取 HTTP 状态而非 CONNECT 状态，尚需修正后验证，不能将该次记录标 PASS。
- 修正测试驱动后，隔离代理对回环地址返回 CONNECT 403，定向实验 PASS，证据见 `.cache/r62-egress/fded2d2923a0`。该结果不覆盖其他保留地址、域名重绑定、真实 TLS、SDK 或平台审批账本。
- 增加公共 HTTPS 正向对照后，CONNECT 返回403，记录见 `.cache/r62-egress/60bdaf1d8f17`；代理来源配置与目标 ACL 尚待区分，前述403仅为观察事实，不能证明其由目标地址规则导致。正在补齐访问日志。
- 诊断确认访问来源属于夹具网段，Docker 的 DNS 将 `example.com` 解析为 `198.18.0.114`，被既定保留地址规则拒绝。受控出口不放宽该规则；正向实验使用公开 DNS 查询获得的固定公网测试地址并保留 TLS 主机名校验，不把此替代实验当成真实 DNS 验收。
- 固定公网测试地址后，代理对回环目标拒绝且公共 HTTPS 请求及证书校验通过，证据 `.cache/r62-egress/38aafb361b09`。正常 DNS、重绑定、SDK 联合与持续资源观察仍 NOT_RUN。
- 扩展的出口夹具对九个选定的 IPv4／IPv6 保留地址拒绝、对固定公网地址完成 TLS 请求，PASS，证据 `.cache/r62-egress/63a44de1be25`。固定地址不代表正常 DNS 或重绑定已通过。
- 隔离 DNS 夹具先返回测试公网网段地址、再返回回环地址，Squid 首次 CONNECT 成功、地址更新后返回403；DNS 夹具确认两次应答，PASS，证据 `.cache/r62-local/dns-change/run-1794047064995277400`。该实验限固定测试域名及临时代理，生产配置、所有地址范围和 SDK 业务调用仍 NOT_RUN。
- 新增受控出口候选配置生成器：仅接受一个私有 IPv4 工作进程身份，目标拒绝列表与已有实验一致，实际 Squid 语法解析 PASS。候选尚未接入工作进程或部署，不能用语法检查代替实际隔离、DNS 和业务调用验收。
- 在独立内部网络和外部夹具网络实测该生成器的访问规则：指定工作进程可经证书校验访问，另一进程被拒绝，DNS 改为回环后原进程也被拒绝；DNS 夹具确认两种应答，PASS，证据 `.cache/r62-local/proxy-policy/run-1794047683775002700`。仅增加了测试 DNS 配置，未部署生产或覆盖 SDK 业务调用。
- 扩展候选配置联测，在非 root 的独立容器通过已打包父进程／受限子进程、候选 Squid 和 TLS 夹具完成真实 SDK 工具发现，并复验其他工作进程及 DNS 变化拒绝；PASS，JAR SHA256 `25b91be2e050b020567f716487e65e7f911d0d21bb997334dbf239171fb9fb77`，证据 `.cache/r62-local/proxy-policy/run-1794047882747590700`。正式部署、业务工具调用和全部地址范围仍 NOT_RUN。
- R6.2 独立控制面一键入口启动8个服务，能力导入和 MCP 探测消费者各1个，新数据库的文件作业数为0；该配置明确不启动物理 runner 或自动扫描。首次启动分别遭遇 Docker 默认地址池耗尽、测试密钥长度不足及旧容器保留旧密钥挂载；另有缺少既有函数授权造成的文件消息重复交接，原数据库和 FAIL 日志保留，修正后使用新数据库验证，不改写历史。控制面使用的文件 helper 策略仅为元数据夹具，不能证明真实工作区能力。
- 真实浏览器定向验证：Skill 导入、发布、个人绑定及刷新 PASS，证据 `.cache/r62-local/browser-1794052007452366600`；连接器密钥不回显、配置及双用户隔离在 `.cache/r62-local/browser-1794051312301230800` 的对应测试 PASS。同一轮首项登录未及时完成，后续专项 `.cache/r62-local/browser-1794051746022984700` 因脚本把保存中误判为保存完成并提前刷新而 FAIL，均保留；修正脚本等待明确提交结果后才通过。两条通过结果范围分别记录，不代表 runner、远端 MCP 探测、全部响应式及最终候选完整验收。
- 独立环境扩展 TLS 测试服务及专用信任库后，真实浏览器完成凭据导入、经受控出口的 TLS 探测、原请求查询与显式启用，PASS；MQ／持久结果 READY，证据 `.cache/r62-local/browser-1794053117212428100`。9 个容器配置上限合计 2688MiB；单次事后采样 API 337.5、worker 235.9、MQ 107.8、对象服务 82.68、出口 10.22、测试服务 13.56MiB，非绝对峰值。业务 MCP 调用、材料 runner 挂载、完整观察和最终候选仍 NOT_RUN。
- 独立启动入口的停止路径增加精确工程及服务身份校验，模拟测试 2 项 PASS，覆盖先停应用后停依赖及未知服务拒绝；最终实际停栈仍待阶段结束时验证。
- `CapabilityMaterializationTest` 两项纯领域测试 PASS：顺序无关的材料清单哈希、任务／包身份改变、越界路径、文件目录冲突及容量拒绝。尚未接入 runner 或执行实际挂载，不构成隔离验收。
- `SkillArchiveValidatorTest` 8 项 PASS，包括对所选版本的压缩包 SHA256 和完整文件路径、大小、哈希清单逐项比对；此校验尚未接入 runner，不能替代材料下载、只读挂载或实时授权验收。
- `SkillValidationServiceTest` 3 项 PASS，包含独立材料流式暂存、下载期间撤权、哈希不符和临时文件清理；存储使用内存夹具，真实对象存储下载、任务授权与 runner 的集成仍 NOT_RUN。
- 无网络 Linux 容器中 `SkillMaterialExtractorTest` 1 项 PASS、`SkillValidationServiceTest` 3 项 PASS：私有目录逐文件解包、清单校验及撤权清理；定向复验的解包源码 SHA256 `ce73ecfe3843edb985ee3f88dfbeda9e79dfa6a414b66ed2e2a67eb476090e38`、暂存源码 SHA256 `e4cc79f4861dbab4151ca0564e332dff91bbe9d962793109fdc82193c35a187f`。验证仍未覆盖签名 runner、挂载和真实运行任务。首次离线构建的参数及构建工具版本不符分别失败，随后使用项目固定 Maven 3.9.11 通过；后续单类修改采用已编译依赖的定向 Linux 验证。
- `SandboxPolicyTest` 7 项 PASS，新增仅限 R6.2 验收命名空间的工作区路径，并拒绝越界及近似路径；这只是路径配置测试，不代表实际容器或真实挂载通过。
- 本机 Docker 运行时清单不含 runsc；云端只读预检确认 runsc 可用，但 `/srv/agentplatform` 可用空间约7.17GiB，尚不足以同时预留独立4GiB测试盘和5GiB安全余量。待测试夹具完成后按已授权冷归档流程核对旧测试产物保护副本及引用，再决定是否回收；未执行生产停机或清理。
- 2026-11-07 用户明确要求清理旧镜像及临时文件。已精确清理历史测试／旧版本应用镜像、已停止的旧应用及验收容器和6个旧传输包，未删除卷或业务数据；云端根盘复核约11.15GiB可用、R5 Host 健康 HTTP200。原位 R3/R4 回退容器和镜像已按用户仅保留最新版本的要求移除，不能继续声称旧容器可原位回退。清理详情在云端 `/srv/agentplatform/operations/r62-cleanup-20261107`；实际建立测试盘前仍须复核余量。R5 切换 helper 本机副本和客户端哈希已与云端同步，只读 status 验证11服务在线，未执行切换。
- 2026-11-07 独立短窗口：新增4GiB预分配 XFS 测试镜像，在 runsc/systrap 中校验工作区可写、选定材料实际只读且容器 inspect 身份匹配，探针 PASS；测试容器移除、XFS卸载／loop释放，原 R5 11服务、重启策略与 readiness 恢复 PASS。测试镜像保留于 `/srv/agentplatform/acceptance/r62-mount-poc-c15bd087764e44d983f54b2868d3f7d3/test.xfs`，后续必须校验身份再复用；此探针直接使用 Docker，不代表 runner 签名／数据库持久链路已通过。维护后根盘可用约7.21GiB；不需再次分配4GiB，仍需在复用前验证5GiB余量。

- 全新和 V22→V23，真实 PG／MQ／S3、并发、幂等、额度及依赖故障。
- ZIP 20MiB／展开100MiB／1000文件，路径、链接、归一化和资源缺失；双用户与管理员隔离。
- MCP 真实 TLS 夹具、出口实际地址检查、schema变化、密钥撤销、审批、未知结果不重放。
- 精确材料 manifest、只读挂载、撤权和旧策略兼容；本机缺 XFS／gVisor 时独立云端短窗口并恢复 R5。
- Maven／架构、前端类型／单测／build、契约、链接、strict 和真实浏览器；最终候选180秒资源观察。
- 总限额2887MiB、宿主余量768MiB、根盘余量5GiB；失败保留证据，不提前标 VERIFIED。
