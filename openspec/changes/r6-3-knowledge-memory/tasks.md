## 1. Plan/OpenSpec 与兼容实验

- [x] 1.1 同步远端R6基线并记录step分支、唯一change与OpenSpec strict结果
- [x] 1.2 更新正式Plan、ADR-20、HC-1、EX08/EX09/CP03和R6阶段台账（包含本次压缩实现偏差）
- [x] 1.3 锁定ONNX Runtime/DJL及E5模型/tokenizer SHA；真实本地索引执行验证输出与检索路径
- [x] 1.4 测量模型冷加载前后进程RSS并验证共享heavy-slot准入（PASS_LIMITED：通过Java /proc/1/status VmRSS/VmHWM记录冷查询前后值，并以数据库槽持有SANDBOX时拒绝ONNX准入；物理renderer/runsc在云端单独验收。本机不安装或运行沙箱，未证明完整应用栈与物理sandbox同时负载）
- [x] 1.5 在云端 Linux 运行物理 renderer/gVisor 验收（DOCX/XLSX/PPTX官方runsc物理转换烟测PASS；该云端独立组件验收不等同于应用签名TEXT_EXTRACT链路；本机没有也不要求runsc）

## 2. 数据契约与索引管道

- [x] 2.1 更新OpenAPI/事件/DTO及生成客户端，冻结V24字段、索引、权限和定位契约
- [x] 2.2 新增V24加法迁移、pgvector扩展/bootstrap角色与最小权限（真实新库安装通过）
- [x] 2.3 实现KnowledgeIngestionService及类型化Mapper/PO/XML和幂等提交
- [x] 2.4 复用TEXT_EXTRACT资产链并生成版本化source-location manifest（PASS：云端官方runsc签名runner完成附件上传→TEXT_EXTRACT→私有S3哈希读回→知识索引READY及语义标记命中；供应商调用0；收据见acceptance-index）
- [x] 2.5 接通outbox/confirm/claim/ACK、owner/fence/租约及ordinal断点续建（真实PG/MQ主成功路径通过；ACK丢失、重投/续跑等完整故障矩阵仍未完成，见6.2）
- [x] 2.6 完整build校验后原子激活；失败保留旧active，删除/撤权即时不可见（PASS_LIMITED：隔离预提取文本夹具注入提取身份不匹配，新build失败且前一已提交版本保持ACTIVE并可搜索；知识专属ACK丢失/重投未单独注入）

## 3. 向量与检索

- [x] 3.1 接入离线ONNX E5编码器与锁定tokenizer；锁定哈希及真实本地索引主路径通过
- [x] 3.2 实现授权候选前置、精确vector ranking、全文与中文词法检索、RRF融合（固定中英/跨语言10项均Hit@8，独立无答案集零误命中；质量与规模证据边界见3.5）
- [x] 3.3 实现知识库、文档索引状态、重建、删除、搜索API与严格事件
- [x] 3.4 交付知识管理、来源定位和搜索页面及权限/失败反馈（真实浏览器限定矩阵PASS：管理员搜索、记忆页、受限账户隔离、375/768/1440无横向溢出）
- [x] 3.5 固定中英/跨语言/无答案集并完成Hit@8与10k/20k p95实测（10/10 Hit@8；无答案0命中；10k p95 110.75ms，20k p95 200.53ms。规模夹具由1个真实双语来源加合成无关向量行组成，不代表广泛语料质量）

## 4. 个人记忆生命周期

- [x] 4.1 实现显式候选、source ownership校验、PENDING/ACTIVE状态与CAS约束
- [x] 4.2 实现confirm/reject/edit/supersede/delete、scope隔离、审计和DB时钟到期（代码完成）
- [x] 4.3 实现个人记忆候选/活跃列表、审核、冲突、复核和删除页面（知识/记忆真实浏览器限定矩阵PASS；完整键盘及200%缩放未验）
- [x] 4.4 验证并发topic唯一、过期边界、撤权后不召回与管理员隔离（PASS_LIMITED：本人/工作区归属、跨空间拒绝、管理员/受限账号隔离、并发同topic唯一及FACT/PREFERENCE/PROCEDURE三类DB时钟边界有API收据；来源删除后搜索及后续任务零知识引用；来源撤权竞态另由应用测试覆盖。不是长期墙钟观察或所有生产ACL竞态矩阵）

## 5. Typed上下文与安全压缩

- [x] 5.1 扩展纯Java MemoryPort/RetrievalPort为带source/version/hash/location/required/degrade的结果
- [x] 5.2 在ChatExecution.beforeModel受保护bundle接入上下文装配并提交前重核ACL/fence
- [x] 5.3 评估AgentScope compaction适用边界；现有局部探针不足以证明平台预算/账本/权限语义，故选定唯一平台投影
- [x] 5.4 实现70%重复assistant文本去重、80%有界抽取投影；不产生额外COMPACTION模型调用
- [x] 5.5 实现context projection/watermark/source manifest CAS、恢复兼容及只读summary API/UI
- [x] 5.6 验证恢复/撤权/取消/失败时canonical ledger不变（PASS_LIMITED：真实数据库提交投影并记录epoch/hash，重启API后读回一致，canonical历史hash前后不变；单测覆盖whole tool pair、必需输入超限及撤权后二次校验。投影提交中取消和强制数据库事务失败未单独注入）

## 6. 端到端验收与收尾

- [x] 6.1a 完成独立新库V24安装及真实PG/MQ/S3索引主路径（索引输入为预提取文本夹具；真实供应商调用0）
- [x] 6.1b 完成V23已有身份数据升级V24兼容验收（独立空测试库先迁至V23，创建既有身份/个人空间，再迁V24；1/1 PASS，见verification与专用日志哈希）
- [x] 6.2 完成双用户/管理员隔离、索引失败保留旧版本、来源撤销及消息交接回归（PASS_LIMITED：签名TEXT_EXTRACT主链、隔离、旧active保留、删除后不召回及记忆边界已有证据；知识路由专属ACK丢失/重投未单独注入，来源撤权受限于当前夹具与授权测试，未扩称完整故障矩阵）
- [x] 6.3a Maven、前端typecheck/81项单测/build、契约生成/非法fixture、链接、OpenSpec strict通过
- [x] 6.3b 受影响页面真实浏览器回归（真实API/Edge：知识搜索、记忆页状态筛选、受限账户隔离、375/768/1440无横向溢出、搜索按钮可聚焦、暗色主题选择保留、无运行时错误；200%缩放与完整键盘操作未验）
- [x] 6.4 完成预算准入、180秒采样与固定证据索引（PASS_LIMITED：184.9秒应用/依赖采样PASS，2880MiB上限配置、Java进程VmRSS前后样本及数据库共享槽拒绝均有收据；物理runsc只在云端执行，不在本机启动sandbox；未验证全服务与物理sandbox并发峰值）
- [ ] 6.5 全部强制项PASS后完成step→phase→main两级保护PR、归档和台账/Skill更新
- [ ] 6.6 从最终干净main准备正文与脱敏证据双副本，校验SHA256并保留至少30天
