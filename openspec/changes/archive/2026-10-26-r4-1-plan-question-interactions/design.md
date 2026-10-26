# R4.1：持久 Plan、补充信息与文本续接实施设计

## 决定与边界
用户已批准 Plan，本阶段 IMPLEMENTING，不是 VERIFIED。基线 fdeaff6（文档纠正 PR105/106；生产仍为 R3）。phase/r4-plan-permissions → step/r4.1-plan-question-interactions；唯一 change r4-1-plan-question-interactions。Java21/Boot4.0.8/AgentScope2.0.4、六模块、MyBatis、Spring 事务和 AMQP 不变。新增 V15，不修改 V1–V14；runtime baseline schema1。

R4.1 交付 PLAN_APPROVAL、QUESTION、同任务文本续接；R4.2 风险及范围授权，R4.3 原业务工具一次批准恢复，R4.4 工具贯通与发行。只接收文本和当前对话历史；Plan 确认只授权文本，不授权文件/shell/工作区读取。云方案 DEFERRED，不合并其 delta、不归档它；本地 gVisor/XFS/helper/S3 继续有效。

## 唯一执行链与 SDK 实验
保留 AgentScope HarnessAgent/Toolkit/MiddlewareBase、AgentRun.cancel/termination 和精确 ConfirmResult，不另写推理循环。先用真实 SDK 验证结构化控制调用 → ASK → 停止并保存 → JVM 退出 → 新 attempt 读回与精确确认。失败留证，不先开放 UI。

类型化 ToolDefinition 保存 JSON Schema 及 CONTROL 分类；原 Call 作为已校验调用事实。旧探针提供兼容定义转换。平台仅注册 platform_plan_write、platform_plan_review、platform_ask_user；不开放 SDK 原生本地 plan 文件工具或带权限放宽的 plan_exit。plan_write 正常执行数据库修订；plan_review 的参数为空，审阅绑定由平台保存，必需问题 ASK，可选问题非阻塞。每轮最多一个 CONTROL，完整轮及调用清单先提交，beforeControlBatch 再持久化控制意图，然后 SDK 进行权限判断。权限检查不可产生数据库业务副作用。

DeepSeekChatProvider 通过现有 OpenAI 扩展传入 ToolSchema，合并流式 tool calls，结束后严格 JSON/DTO 校验，再进入完整轮事务；thinking disabled，usage 逐物理调用记录。工具正文不进入公开 assistant 文本，隐藏推理、控制信息与 key 不公开。模型冻结身份保持不变。

## 模块与事务
types 放内部纯值、状态及版本化工具定义；domain 放 Plan/Interaction/ControlInput/Budget 值与仓储端口；app 的 PlanApplicationService、InteractionApplicationService 协调短事务，ChatExecution 只组合现有 Harness；infrastructure 的类型化 Mapper/PO/XML、SDK/provider 适配实现端口；trigger/API 使用类型化 DTO/Bean Validation/现有 SecurityFilterChain；Vue Query 管服务端事实，Pinia 仅草稿及 UI。

V15 增加 plan、不可变 plan_revision、interaction、interaction_answer、control_input、resume_binding，以及任务冻结 token/费用额度、累计活动毫秒及开始时间。所有关联含 space/task/attempt、generation/fence、唯一键、修订 CAS、哈希。平台账本权威，SDK 私有状态仅恢复框架。锁序维持全局槽→会话→task/dispatch→attempt→调用/预算/计划/交互/checkpoint；事务内不等待模型、MQ、HTTP。

完整轮 → 控制意图 → SDK ASK → WAITING_INPUT checkpoint → SDK/provider/回调实际停止 → 停止收据 → 释放槽。错误/取消不能封存成等待；无法证实停止 BLOCKED 保留槽。原 attempt 结束 WAITING_INPUT；同会话新提交被阻止，其他会话可使用槽。

答案流程：实时授权/版本→答案 CAS、交互水位、恢复绑定及 outbox 同事务→MQ confirm→领取提交→ACK→新 attempt。校验原 COMMITTED checkpoint、哈希、bundle、停止收据；原 checkpoint 不改写，答案是独立控制增量，不伪造 USER 消息。保持 taskId、模型/凭据/预算身份；ConfirmResult 只绑定原 call，无 PermissionRule 放宽。UNKNOWN 不重放。

## 交互规则
恢复绑定另保存不可变的 attempt 绑定历史。回答已领取但尚未开始物理调用即崩溃时，仅在精确停止证明、原 checkpoint/答案绑定、零物理调用及完整哈希校验全部满足后，允许新 attempt 重新绑定同一答案；不覆盖原绑定。控制意图已提交而等待未封存时，恢复后的 ASK 以新交互身份绑定当前 attempt，保留原期限并拒绝旧确认。任何 UNKNOWN 调用仍阻断恢复。
PLAN 支持 EXECUTE_PLAN、REVISE_PLAN、REJECT_PLAN、DELIVER_PLAN。直接编辑 If-Match 创建不可变修订，关闭旧审阅并建立新审阅水位；原 SDK review call 通过 resume_binding 显式绑定当前审阅。旧确认 412。拒绝回到讨论，不等于取消；仅交付方案直接成功，不再请求模型。执行结果是已批准修订的文本续接。

QUESTION 支持选项、自填，optional 才能 SKIP；可选问题非阻塞，在下一安全点消费一次，任务结束后不能复活。计划审批 7 天、问题 24 小时，到期关闭交互并明确结束等待，永不自动允许。计划 128KiB，答案/修改要求 32KiB，按 UTF-8 字节校验；超上下文保留内容并明确拒绝，不截断。取消等待任务关闭交互，释放可用预留；取消/答案/过期使用相同锁序和版本。

## 预算修正
业务没有请求次数预算，删除 ordinal>4、恢复 calls>=4、4-calls，以及 SDK maxIters(8)。SDK 实现上限 Integer.MAX_VALUE 仅满足 API 正整数约束，真正准入由 token/费用/累计活动时间控制。保留有限故障退避/空输出修复，不允许无限错误重试。

历史草案（已被下文用户每日额度决定替代，非当前实现）：初始任务 token 上限沿用 36864；费用上限沿用已生效的模型价格政策，独立冻结，不通过本次修正扩容。历史费用从冻结价格/实际账本回填，不能信任历史 reserved_micros 的通用默认值。提交时账户预留整任务；开始调用将任务可用额度转为调用预留。已知 usage 结算时账户 reserved 减实际消耗、consumed 加实际消耗，调用未用份额返还任务可用；UNKNOWN 保留全部预留。终态只释放未用额度，等待不清零。恢复按冻结上限减已消费/未决额度计算，绝不按调用次数补额度。结算、退款幂等 CAS，账户/任务/调用守恒。

累计活动时间 120 秒，排队/用户等待不计入，回答和新 attempt 不重置；旧首调用墙钟 120 秒和 execution_deadline 必须同步替换。上下文8192、输出1024不变。真实验收仅受保护入口最多20次物理请求（重试/UNKNOWN也计数），独立于生产预算和历史计量。确定性通过后只补必要 Flash 闭环，余额错误立即停。

## HTTP、事件及页面
先 OpenAPI/严格事件 schema 再生成客户端。task options.planMode boolean 默认 false，权限 RISK_BASED。GET/PUT /api/v1/tasks/{id}/plan；PUT If-Match。POST /api/v1/tasks/{id}/interactions/{interactionId}/answer：Idempotency-Key + expectedVersion，202。snapshot.pending 为有界类型化联合；task WAITING_INPUT；interaction.opened/resolved、plan.revised 各自严格 payload。所有入口身份/空间实时授权，管理员不越权；写入保留 Origin/CSRF。

Vue Plan 开关、PlanCard、QuestionCard、修订差异和等待/恢复状态；“仅交付方案”放 PlanCard，不添加 Agent 选择器。响应丢失/刷新/版本冲突查询原交互，不重复提交。等待同会话禁止普通发送，退出清缓存，375/768/1440、200%缩放、键盘及浅暗主题。

## 验收、交付和回退
独立 agentplatform-r41-local 数据/网络/凭据；总容器额度沿用2880MiB且<=2887MiB，宿主留768MiB，不启动业务 sandbox；构建与运行串行。先 SDK 跨 JVM 窄实验，再逐批后端/真实 PG/MQ，最后浏览器/必要真实 Flash。

覆盖完整计划/修改/确认/仅方案/必需可选问题；双标签/幂等/旧版本/过期/撤权/取消；ASK/checkpoint/答案/MQ崩溃窗口；坏哈希/bundle拒绝/UNKNOWN不重放；低 usage 超4调用和超8轮，额度守恒与等待不刷新；R1聊天/凭据/诊断/SSE/R2恢复兼容。Maven/DDD/持久化边界/Mapper/XML、前端类型/单测/build、契约非法输入与重生成无差异、链接及 strict。

功能默认关闭，云端现有R3不变。失败留当前 step 修复，不双写、不改旧迁移、不标通过。全门禁后 step→phase→main 保护 PR（Actions关闭），归档本 change，更新局部/全局台账及 Skill，正文与脱敏证据分包。最终仅 R4.1 VERIFIED、R4 1/4 未 RELEASED；R4.2另行 Plan/OpenSpec。


## 2026-10-26 用户补充决定：统一可配置每日额度（优先于原任务额度初值）

普通用户默认每天 1,000,000 tokens，按输入+输出总量计量（缓存输入包含在输入总量，不重复相加），所有模型、PLATFORM/USER 来源、空间和任务按 userId 汇总。默认按 Asia/Shanghai 自然日，数据库时间确定 [start,end)；策略可配置额度、时区和用户覆盖。平台管理员治理角色（包括 lailihao_admin）为 UNLIMITED：仍计量、审计并保留 UNKNOWN，不用用户名字符串判断，不绕过授权、并发、上下文、输出和累计活动120秒限制。角色变化在下一物理请求前实时读取。

不再以旧的每任务36864tokens或隐含4次调用的费用预留作为新的用户成本门槛。通用额度策略分离 subject(USER)、metric(TOKENS/COST_MICROS)、window(DAILY)、limit/UNLIMITED、revision/effectiveAt。默认仅 TOKENS=1000000 生效，费用仅统计，COST_MICROS 默认不阻断；将来明确配置才启用费用额度。配置通过受保护运维服务/命令，数据库版本CAS及审计；不为本阶段扩大账号管理页面。

V15 增加 quota_policy、quota_bucket、quota_reservation（唯一物理callId、策略版本、userId、windowStart/end、预留/实际/状态），由 QuotaAdmissionService/类型化Mapper/Spring短事务实现。额度锁固定放在执行槽之前，其他路径相同顺序；禁止先锁task再反向锁quota造成死锁。每个物理请求按当前冻结上下文/输出上限保守预留，调用前提交后才发请求；已知usage原子结算并释放差额，UNKNOWN保留预留，未发送证明可退款，重复结算幂等。不能用实际小usage预期先发后记账。

跨日不删除或清零旧bucket：新请求使用新日bucket，旧调用迟到结算仍回原bucket。旧UNKNOWN继续作为跨窗口未决占用参与准入，不因午夜免费重放或获得退款。任务等待不占执行槽，不为整个未来任务预占多轮token；新attempt/回答不能重置quota或累计活动时间。降低配置不得修改既有事实/撤销已发请求，后续按新策略拒绝。禁用账号或撤权仍优先拒绝。

GET现有用量投影应给出当前生效额度/UNLIMITED、已用、预留、剩余、resetAt和策略版本；限额错误区分平台DAILY_TOKEN_QUOTA_EXCEEDED与供应商余额/限速，不自动换key。页面给出重置时间；不实现充值/自动购买/模型降级。

参考产品交互而非复制其未公开算法：[WorkBuddy官方用量说明](https://www.workbuddy.cn/docs/workbuddy/Usage)提供套餐状态及按任务/模型消耗明细；[ChatGPT官方定价](https://chatgpt.com/pricing/)说明套餐差异。本项目选择可解释的自然日tokens策略是用户决定，非声称与两者计费算法相同。

新增必测：普通默认/用户覆盖/管理员UNLIMITED但计量/改角色、双任务抢剩余额度、跨日迟到usage与UNKNOWN、重复退款、配置下调/时区与窗口边界、等待恢复不重置、费用默认不阻断。真实验收20次物理请求独立保留，不作用于真实用户。
