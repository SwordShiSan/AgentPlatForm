## Context

R6.2 的 R6 主线已在 a5c4e485 同步，phase 文件树相同，step/r6.3-knowledge-memory 从 phase 建立。R5 提供文件版本、私有资产、SeaweedFS、TEXT_EXTRACT持久作业、PG/MQ和共享 gVisor heavy-resource slot。当前实际模型入口在 platform-app 的 ChatExecution.beforeModel；SDK compaction/memory hooks被显式关闭。canonical checkpoint history 和 model/tool账本是执行事实，不可被摘要重写。

## Goals / Non-Goals

**Goals:** 持久、空间隔离、可重建知识索引；用户显式审核记忆；真实上下文端口与单一压缩实现；来源版本、撤权、预算和恢复可审计；所有本地资源限制和 R6.4范围清晰。

**Non-Goals:** 云发布/真实供应商、Skill/MCP自动装配、自动提取记忆、默认对所有普通对话注入知识、OCR、独立调度器、SDK第二循环、对象GC、手工压缩或完整派生清理。

## Decisions

1. **V24是加法数据面。** knowledge_base/document/index_build/chunk、memory_item/audit、context_projection/compaction使用空间与用户复合外键、版本、hash、CAS和索引。Vector扩展由迁移或独立受保护bootstrap按最小角色创建；API运行角色不升权。全新安装与V23升级都必须通过。
2. **索引来源不可变。** 仅接受授权 fileVersion/attachment asset；R5隔离提取链产生正文和定位manifest；构建经 outbox/MQ可重入。chunk唯一键与持久ordinal允许续建。完整校验后以短事务切换active版本；失败时旧版本不变。撤权先阻断查询再做清理。
3. **本地向量方案。** Xenova multilingual-e5-small INT8固定commit 761b726dd34fb83930e26aab4e9ac3899aa1fa78；按该提交的 `?blobs=true` 官方元数据锁定 `onnx/model_quantized.onnx` 118308185 bytes / SHA256 `f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193`，以及 `tokenizer.json` 17082730 bytes / SHA256 `0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39`。ONNX Runtime 1.31.1与DJL tokenizer 0.38.0。前缀、tokenizer边界、masked mean pooling、L2归一化由真实兼容probe确认。推理共享现有heavy slot，离线加载。若ONNX包hash或tokenizer不匹配，readiness失败，不切远端。
4. **先ACL再排序。** SQL先构造space/source/version有权候选，再精确向量和全文检索；每路top20，RRF k60。首版不依赖全局HNSW过滤，以避免过滤后授权候选不足及侧信道。固定10k/20k测质量和性能后再决定索引优化。
5. **记忆由用户显式控制。** 只有用户产生PENDING并确认后才ACTIVE。USER scope为默认；可选工作区只能是本人拥有的私有空间。DB时间驱动生命周期，一项scope/subject/topic只能有一个ACTIVE。删除/撤权先阻断召回，审计仅哈希与状态事实。记忆正文限长，来源须验证归属与版本。
6. **Typed retrieval/context contracts.** domain提供EvidenceHit/ContextItem/ContextProjection/CompactionRequest等纯Java值，端口不暴露Spring/AgentScope/MyBatis类型。候选包含来源resource/version/hash/location、required、token estimate和降级码；注入模型前重新校验ACL、当前来源版本和task owner/fence。仅受保护R6.3任务策略连接这些实现，普通聊天能力自动装配留R6.4。
7. **只选择一种压缩实现。** R6.3采用唯一的平台确定性抽取投影 `ContextProjectionService`，不启用 AgentScope `ConversationCompactor`，不发起额外摘要模型请求。现有SDK公开API探针只证明了组件/文本摘要与取消等局部能力，没有证明它与平台预算、撤权、canonical账本、审批和完整工具事实边界兼容；不能据此把SDK compactor接入生产路径。AgentScope的flush/offload/prune及自动淘汰继续关闭。
8. **压缩是独立派生投影，不是事实摘要。** 输入估算达到70%时仅去重可选的重复assistant纯文本；超过80%且可压缩的旧assistant纯文本存在时，将其抽取为带“历史非可信文本”标记的有界片段。USER/SYSTEM输入、附件/知识/记忆来源引用、审批内容、完整工具调用及观察保持原样。投影须真实缩小且满足输入上限才通过CAS提交；不保证达到60%目标，也不宣称具备语义摘要能力。必须项单独仍超限时保留原投影并明确拒绝。CAS键包括task、bundle、ledger watermark、context epoch、source manifest hash。canonical历史不变；失败保留旧投影。
9. **避免隐藏限额。** 当前 ContextPolicy 的1M模型能力、256K默认任务窗(可配64K–512K)、4096默认/8192最大输出与5%/8192安全余量不改。只移除妨碍可压缩历史到达安全点的旧早期拒绝；100MiB、8MiB状态、16MiB checkpoint及必需项限制不放宽。新任务冻结新策略，legacy bundle/checkpoint维持原规则。
10. **同步事件与安全API。** OpenAPI先更新再生成；状态事件和outbox同事务。管理员治理权不扩展内容访问权。context-summary只读估算/水位/状态，不返回prompt、摘要全文、secret和越权来源文本。

## Risks / Trade-offs

- [模型冷启动或RSS超过预算] → 首先在完整轻量profile实测，加载按需单实例，推理占共享slot；总容器限额≤2887MiB。
- [pgvector扩展权限差异] → 分离bootstrap与应用迁移最小权限，先在真实local PG验证全新/V23升级。
- [提取位置无法精确映射] → 仅返回提取器提供且绑定原文hash的位置；缺失定位返回“段落/文本范围未知”，不伪造页码。
- [RRF质量或延迟不达标] → 保留固定语料/版本/数据量报告；不删除ACL过滤或改用未评估索引来达门槛。
- [抽取投影遗漏旧assistant表述] → 该投影明确为非权威历史提示；所有用户/系统输入、来源引用、审批和工具事实不抽取，必要信息超限时阻断，不伪称语义摘要。
- [上下文压缩增加隐藏计量] → 本阶段不额外调用模型；普通模型调用仍按既有预算/费用/停止屏障记录，远程供应商调用0。

## Migration Plan

Physical renderer/gVisor execution belongs on the cloud Linux host. Workstation Docker is used for builds and database-backed feature acceptance and is not expected to provide `runsc`; local acceptance must not emulate or claim the physical sandbox gate. The cloud-only `preview-runner` profile exposes the signed extraction route and deliberately excludes workspace allocation, shell, and environment runner configuration.

1. Strict规范通过后先探测ONNX/tokenizer/hash/RSS与模型安全关闭加载。
2. 编写V24及pgvector bootstrap/权限脚本，测试空库与V23升级。
3. 扩展R5 TEXT_EXTRACT source locator及持久索引build/outbox/chunk。
4. 实现知识管理/search与混合排序，再实现记忆生命周期。
5. 在受保护R6.3 bundle接Context ports和唯一CompactionPort，所有变更在local。
6. 完成定向真实PG/MQ/S3、浏览器、质量/资源证据；之后两级PR并归档。数据库迁移加法且禁止自动降级schema；回退时关闭R6.3 feature bundle保留V24数据，旧bundle不读取新投影。

## Open Questions

已用锁定ONNX/tokenizer执行本地索引与向量检索。SDK compactor不接入：平台所需的账本/权限/预算语义没有由局部SDK探针证明，故保留唯一平台抽取投影；此边界和本地功能证据已记录在 verification.md。真实renderer、性能门槛和持久恢复等仍按未运行项记录，不以局部功能代替。
