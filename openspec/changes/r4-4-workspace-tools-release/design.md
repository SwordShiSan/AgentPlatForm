# R4.4：工作区业务工具贯通与R4发行实施计划

## 实施纠正：审批资源版本稳定性（2026-10-29）

目录扫描在工作区根目录排除精确 `.platform-ready-<canonical UUID>` 启动挂载探针；它属于控制面收据，不是用户文件或资产。普通相似名称及子目录同名文件继续扫描，文件种类、链接、分页和挂载校验不放宽。上传版本提交同时写入已核验的 observed_sha256/observed_bytes，避免第一次只读扫描补齐同一内容身份时使审批无故失效。实际内容、版本、缺失及归属变化仍推进修订并拒绝旧批准。helper 镜像和策略身份随改动重新固定，旧活动任务不得热切换。

实时授权函数本身会对space_storage持有共享锁，因此文件runner、环境准备/查询/执行/使用、受信helper及空闲维护必须在sandbox容量锁之前取得相关授权行锁；父任务已持有quota与storage时沿用其顺序。停止观察只预取相同锁，不因撤权拒绝已证明停止事实，原执行授权和fence门禁保持。禁止在持有sandbox后首次请求storage锁，读查询和后台扫描同样适用。

## 当前状态与批准决定（2026-10-27）

IMPLEMENTING；全部R4.4新功能门禁NOT_RUN。main基线c15cb410228ddb6a6df91796c1e090699eea6546，phase a9125b211373dd16f4b476fff86c3ab1b14129f3，同树677e39404c79bc47006cf1d746493a0dc74087eb。R4当前3/4 VERIFIED、未RELEASED，累计19/40；仓库记录生产R3，实际上线身份在维护窗口前只读核验。唯一change r4-4-workspace-tools-release，step/r4.4-workspace-tools-release。

目标：自然语言→Plan/澄清→同任务确认执行→文件和通用shell→持久结果与版本读回→R4发行。不启动R5。云方案DEFERRED、不归档合并；继续本地Docker/gVisor、XFS、受信helper和SeaweedFS。用户选择：文件＋通用shell；Plan同任务转执行；获准后自动准备/唤醒；每次shell停止整代容器；RISK_BASED逐次确认，FULL可自动执行。

## 1. 能力与产品规则

- workspace_list/read/write/shell属于冻结、版本化的生产目录。未绑定工作区仍为普通文本；历史task/bundle不追溯获得新工具。
- list返回受权目录、稳定ID、版本与有界分页；read显式fileId/version及有界读取；write仅有界UTF-8文本，新建/显式替换，冻结baseVersionId/revision和正文hash，复用R3版本和额度，不新增直接覆盖通道。文本正文最多32KiB，大文件继续公开上传接口。
- shell在gVisor中执行/bin/sh -c，完整命令最多32KiB；cwd为绑定work目录下服务端确认的目录，非交互、无PTY、无stdin。模型不能提供actor、space、宿主路径、endpoint、Docker参数或环境秘密。
- shell可修改/删除整份绑定工作区文件；目录grant不是执行隔离范围。RISK_BASED为每个冻结shell请求ASK，FULL可自动执行；硬DENY/实时ACL/Plan/配额/隔离/fence仍先于模式。管理员免token额度不免内容授权。文件管理UI删除/恢复仍R5，但不宣称shell不能删除文件。
- Plan确认持久记录具体修订并在同task解除规划限制，后续仍逐工具准入；新修订使旧确认失效；DELIVER_PLAN成功结束且不追加模型请求。
- 获准后只自动准备/唤醒原绑定工作区；文件工具仅需存储READY。shell每次完整停止并移除容器，文件/配额保留，后台服务/临时安装不保留；下次新generation重建。

## 2. 框架、端口与数据链路

保持Java21/Boot4.0.8/AgentScope2.0.4、六模块、MyBatis/Spring事务、SpringAMQP/docker-java3.7.1/Nimbus/AWS SDK。领域纯Java。ToolBase薄适配注册Toolkit、串行、DEFAULT/空ConfirmResult.rules，只有既有Harness，无SDK本机文件/shell工具。

完整模型轮与原tool_operation→服务端ToolIntent→策略/原调用审批→owned startTool＋票据消费＋子作业绑定→outbox/MQ确认/领取提交/ACK→签名runner→原receipt/停止→S3版本/结果→同事务工具观察及事件→SDK/checkpoint。

生产Provider校验版本化工具schema，非思考OpenAI扩展真实流式tool_calls；完整finish reason/JSON/参数校验后才能执行。每轮一个业务调用且不混CONTROL。聚合期间累计参数64KiB，超限/截断/多业务调用全部拒绝执行，不截短后执行。上下文计入实际目录schema、提示和结果。

ToolExecutionPort保留兼容result方法并增加纯Java生命周期句柄(result/cancel/terminated)，纳入ExecutionControl的5秒屏障。取消/撤权/数据库失权停止精确原runner作业；关闭HTTP/Future、租约过期、exec404本身不是停止证明。停止不明保留槽/租约/预留并BLOCKED，只查询原command，不重新执行。

V18加法，V1–V17不改，runtime schema1。增加服务端工具意图、原operation到activation/file/use作业的唯一关联、产物采集和结果引用、FAILED事实；workspace.content_revision由文件提交及shell写事实/不明写单调推进，环境生命周期本身不推进。

审批冻结完整命令/argsHash、cwd、相关版本/content_revision、image/policy及预绑定activation意图。environment.version会因prepare/wake变化，不能继续把它当稳定批准hash。原activation READY后将物理environment/generation/command/policy一次性绑定；审批hash不改写。其他内容/资源/权限变更使准入失效。任务owner/fence与环境generation/fence分别存储/签名/验证。

统一短事务锁序：用户日额度/space对象额度→全局task slot→conversation→task/dispatch→attempt→sandbox capacity→workspace/data-plane lease→environment/use→tool/approval→file/version/object/receipt。带task的子作业先走统一资源事务协调器；独立文件/生命周期仅取同序资源子集，不反调持task锁。提交前再验租约、权限、owner/generation/fence；外部等待均在事务外。

shell执行后停止整个generation并确认exec/容器/回调退出，独立helper再CAPTURE/SEAL。最多16个声明输出，每个冻结maxBytes，总计≤100MiB；前置预留、流式S3、长度/hash校验后结算并发布file_version。未声明变化标PENDING/CHANGED/MISSING，旧版本不冒充当前事实。UNKNOWN不释放未决预留/数据租约，不重跑原命令。已知非零退出/冲突/额度拒绝为FAILED；SDK得到真实错误观察，后续修正是新operation。

stdout/stderr合计64KiB并标截断；结果>32KiB入私有S3，模型摘要≤8KiB。无完整文件byte[]/Base64/DB正文大字段；工具原始参数有明确32/64KiB协议边界，不成为大文件上传旁路。

shell的PREPARED→RUNNING准入事务同时预留全部声明输出上限和64KiB结果对象额度，直接更新既有space_storage聚合。tool_output_capture记录每条输出预留；tool_result_reservation记录原操作的结果预留，不能在内容未知时伪造asset_object长度/hash。采集ID、拟用版本/资产身份及对象key由服务端冻结，真实描述到达后才创建对象事实。后续结算必须对应这些原预留；UNKNOWN保留预留，不由超时退款。此账本不增加第二套额度准入器。

### 文件工具事务细化

`WorkspaceFileToolService`复用现有FileRepository、FileApplicationService、AssetStore及file outbox/worker/runner，不创建第二条物理写入路径。V18的`workspace_tool_file_admission`只记录原工具operation到upload的不可变绑定、原attempt/owner/generation/fence，或尚未产生副作用的明确拒绝；文件状态仍以既有file_operation为准。原工具RUNNING、批准消费、对象额度预留、upload和绑定同事务提交。已知冲突用Spring嵌套事务保存点撤销本次文件准入，再保留FAILED观察；数据库故障则完整回滚，不伪造成业务冲突。传输后，file_operation/outbox与原工具绑定再次同事务提交，禁止先发布未绑定作业。

文件worker和runner通过窄的数据库谓词复核父task所有权、实时授权、凭据、权限修订和原批准Plan；runner不获得聊天/凭据/checkpoint表读取权。带父任务的文件作业先锁额度及task所有权，再取资源子集锁；停止事实的观察和持久化不要求父任务仍可执行，取消后仍能证明实际终止。文件读取只读明确不可变版本，分块校验全对象长度/hash与严格UTF-8，内存中仅保留有界codepoint页，序列化结果不超过8KiB；目录分页同样受结果字节限制，不能把过长结果静默当成完整列表。

### shell可靠交接与旧协议兼容细化

shell沿用父task已经完成的outbox/MQ持久领取及串行Harness；在原operation准入事务中建立唯一shell-use及父attempt/owner/task-generation/task-fence绑定，提交后才允许一次签名POST。结果不明仅查原use，不新增数据库直跑兜底或第二个shell调度器。自动准备/唤醒仍使用既有environment outbox/MQ，写入与采集仍使用file outbox/MQ；所有交接都必须在公开可执行动作前持久化原operation关联。

保留`EnvironmentUseData.Request(file,probe)`的原JSON字段和旧命令/请求hash。shell使用独立版本的类型化请求及内部路由，不能给旧record直接追加shell字段。可抽取纯Java请求公共端口和共享所有权/stop投影实现，但旧HTTP必须仍仅接受file/probe，旧状态codec/序列化fixture必须逐字节回归；新版本才接受shell和显式父任务身份。父task fence与environment fence分别命名、分别校验，不把任一数值当作另一个租约。Nimbus签名继续绑定完整正文摘要和原use身份，runner窄谓词复核对应父账本，而不读取聊天/凭据/SDK状态。

独立内部路由为`/internal/workspace-shell/v1`及其原use查询。仅该版本POST的JSON传输信封上限为128KiB，以容纳64KiB以内参数的JSON转义与服务端身份字段；原参数上限不变，其他路由及所有签名响应继续64KiB。路由对应的具体请求类型在反序列化时固定，跨版本正文拒绝，不能由客户端类名选择实现。一次POST丢失响应后只查询原use；查询不能停止当前仍在退出的同一进程调用。

每次shell先持久化真实whole-container/exec/callback停止事实，再允许受信helper采集。封存声明输出期间仍保留该原use的数据面独占和容量所有权；只有原operation关联的CAPTURE作业且已有精确停止事实才允许进入，独立上传/扫描/环境生命周期继续排队。采集/额度/版本事实提交后，才收敛use、移除原容器并释放槽，防止stop与capture之间出现另一写入者。不另建与现有use/file lease并行竞争的第二套工作区状态权威。

固定helper的`file-capture-v1`先以同一禁止跟随链接的文件描述符校验普通文件、单链接、挂载设备及声明上限，流式计算长度/hash并输出有界描述。runner持久绑定描述并校验额度后发送精确READY帧，helper从原描述符回绕后输出原始字节及32字节hash尾帧。runner的有界输入适配在S3完成multipart前校验尾帧和实际退出；任何变化/短读/迟到错误都保留未决，不先发布版本。helper不接收S3地址、object key或凭据。文件描述符前后stat及二次hash校验防止描述和流不一致；停止后的独占租约负责排除正常写入者，路径不能依赖字符串前缀检查。

采集作业继续使用现有file_operation/outbox/inbox/文件租约，kind为CAPTURE且upload_id为空，不伪造上传会话。独立`FILE_CAPTURE_V1`管理信封通过`/internal/file-captures/v1`传递，原上传/扫描Command保持字节兼容。新信封绑定原文件作业以及不可变capture/源operation/作用域/路径/预留上限/资产与版本身份/生成的object key；真实长度/hash在描述落库和最终签名收据中绑定。worker仍经既有文件MQ消费者持久领取、提交后ACK，不增加第二调度器。文件准入只允许已绑定原shell且有精确KNOWN whole-stop事实的采集作业越过原use的未决互斥，其余上传/扫描/生命周期继续拒绝。

runner的描述事务核验原文件owner/fence及父任务授权，按真实长度/hash创建STAGING对象；helper实际停止可在父任务撤销后记录，必须匹配已登记的容器/exec。捕获成功收据还须匹配先前描述和同一停止hash。runner仅获采集路径/对象身份/描述与停止字段的必要列权限，不得修改预留额度、源调用身份或版本结算状态。worker独立读回S3长度/hash后才同事务发布file_version、对象引用、额度与当前文件投影。

取消/超时的内部停止请求绑定原operation/use和原owner，停止观察不要求仍有业务执行权限。关闭HTTP/Future或仅停止SDK不能替代这个停止请求及实际回执。文件传输同样必须拒绝新的物理动作，但允许失权后保留UNKNOWN/预留及写入真实停止事实；完整停止屏障纳入这些原作业，无法确认时不释放父执行槽。

## 3. API、事件与界面

先契约再生成。新增GET /api/v1/tasks/{id}/operations分页与GET /api/v1/operations/{id}/result有界读回；现有answer端点继续精确ALLOW_ONCE/DENY。Plan/snapshot加执行phase和类型化工具投影；operation事件和状态同一账本同事务提交。

API使用安全投影列/DTO，审批完整command/cwd/输出声明作为受控review字段，仅本人当前space可见，不开放通用call_json/result_json/私有checkpoint权限。写接口会话/Origin/CSRF/幂等/版本规则保持。

ToolCard显示审批/环境准备/执行/停止/版本封存/结果、耗时、exitCode/截断/文件版本链接；UNKNOWN无普通重试。PlanCard确认不宣称直接授予工具资源；FULL说明整工作区执行范围。VueQuery管服务端事实，Pinia仅草稿UI，刷新查原operation，失效清理缓存和传输。

## 4. 实施顺序与验收

先SDK→实际shell→后台子进程停止→helper封存→原操作恢复的纵向实验，再V18/契约、事务、runner协议、采集、API/Vue。独立agentplatform-r44-local/新DB卷网络凭据，复用固定预检，不触碰生产数据。

工程Maven/DDD/Enforcer/Mapper/XML、Vue类型/单测/build、契约生成/非法输入/链接/strict。真实PG/MQ/S3覆盖新安装/V17→V18/生产V14升级、竞争/重复/confirm/ACK/DB与broker故障、旧fence、版本变化、双用户与管理员隔离、撤权/取消、FAILED/UNKNOWN、执行后结果提交前故障、checkpoint兼容。

浏览器Plan同task转执行、RISK批准/拒绝、FULL自动执行、刷新/响应丢失、下载版本，375/768/1440、200%/键盘/浅暗。Linux必须覆盖新通用命令/子进程停止/重建读回/封存；仅按相同产物复用未变的R3隔离证据，不重跑全部历史矩阵。

完整预算从R3实测2880MiB起步，先冷启动/登录/聊天/文件/活动sandbox共存；可调整但总≤2887、宿主≥768、根盘≥5GiB，单task/传输/sandbox/hash，不swap扩容。构建/扫描/受限运行串行。

确定性与真实依赖通过后才最少Flash闭环；本阶段本地+云端≤20物理请求含重试/UNKNOWN，计量不重置，余额不足立即停止。业务不加请求次数上限，普通用户默认可配置1Mtokens/日，管理员无限额但计量；累计活动120s跨attempt不重置，用户交互等待不计入，准备/传输/命令计入活动。

## 5. 晋级、发行和交付

全部强制门禁PASS后固定源码/镜像/V18，保护step→phase→main，R4.4 VERIFIED/R4 4/4但未发行。Actions禁用不伪造检查。

适配现有R3发行/恢复入口，去掉硬编码迁移14和旧probe身份，执行前只读生产图/回退快照。停止写入并一致备份PG/workspace/XFS/S3/MQ/key版本；云端导入同一不可变产物，V14→V18，不导入本地账号/文件。HTTP/IP/Host，DNS/TLS不改。

云端身份/历史/Plan/FULL与RISK/真实文件shell/内部端点封闭，单次至少30分钟混合功能及资源观察。新栈停止后串行独立恢复，验证身份/密文key/文件对象hash/quota/readiness，不发模型。回退前收敛R4任务，旧R3不能接管新bundle；演练R3回退后重启同一R4候选、最终冒烟，更新platform-switch身份并创建production-r4-<sha>。

成功：R4.4 VERIFIED、R4 4/4 VERIFIED、R4 RELEASED；云端R4、旧平台停机保留；R5另行Plan。失败优先恢复同一R3，不改main稳定历史，不自动启动旧业务。归档唯一change（云方案除外）、同步phase、局部/全局/交接/ADR/Skill；最终干净检出后正文与脱敏证据分别两份保护本地及云端副本、SHA核对、≥30天。秘密/SQL/原始浏览器DOM不公开。停止验收/恢复资源，保留卷镜像。


### Production composition and convergence detail

WorkspaceToolsApplicationService is the single application composition behind WorkspaceToolRuntime. Its one bounded IO executor is not a task scheduler: file writes/captures and environment activation still use the existing outboxes/MQ/claim/ACK; shell uses the already durably handed-off parent attempt and one signed original use. A fresh admission ticket may issue one POST only; lost responses query the same ID. Activation is bound in the admission transaction and checks the original operation_execution_binding (including approval-resume attempt), owner/fence, permission and deadline before execution and commit. Environment version is not an immutable approval identity; image/policy/content/permission remain frozen.

All nonempty or empty bounded shell output is stored uniformly in a private result object (including outputs <=32KiB), independently verified and settled against the original64KiB reservation. This extends the required >32KiB storage path without increasing bounds or exposing object keys. The model receives <=8KiB JSON; authorized version links are queried from canonical capture/version rows rather than relying on a truncated model summary.

A stopped-source observation invalidates old directory/file projections exactly once before capture. The existing managed scan scheduler refreshes undeclared changes after the source use releases its data-plane lease. Capture has no SDK or Agent loop. A fixed helper's nonzero exit before any descriptor, plus actual callback/input/IO exit and precise stop, is a known capture rejection; once a descriptor/upload might exist, ambiguity remains UNKNOWN.

Cancellation of a PENDING never-claimed child can persist an immutable negative handoff fact and release only its unused reservation. It does not manufacture a runner receipt. Any accepted/running child requires its exact physical stop. Local worker stop and signed host worker-stop proof cannot release the task slot while a linked runner child lacks termination evidence. UNKNOWN results retain reservations and are never replayed.

Only B20 uses scoped-plan-review-v2 control descriptions and same-task workspace execution guidance. B19 retains the original text-only descriptions and bundle hash. Control input schemas/categories do not change and no description grants operation or resource authority.
