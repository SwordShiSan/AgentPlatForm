# Architecture and decisions
采用现有六模块DDD。domain的RiskPolicy只处理类型化规则；app的PermissionGrantService/OperationAuthorization协调资源、决策和事务；infrastructure沿用Repository→Mapper/PO/XML；trigger负责DTO/版本和HTTP，Vue Query持有服务端事实。

## Submission and persistence
V16新增任务绑定、权限修订、grant、幂等命令及决策审计。提交options.workspaceId可空，只可绑定当前空间READY工作区；绑定与任务/预算/outbox同事务，幂等摘要包含mode/workspace。初始权限加入新配置快照，实时修改不变更冻结配置。历史任务默认RISK_BASED/空绑定，无伪造历史grant。

## Authorization
顺序为实时身份/资源ACL→Plan→环境边界→强制DENY/ASK→mode/grant→确定性风险。服务器scopeId引用确认目录或工作区根；授权绑定task/space/workspace/目录身份、能力和policyVersion，最长24h且终态立即无效。只提供LIST/READ/WRITE，不能赋予EXECUTE/NETWORK/DELETE。NFC目录段匹配，禁止越界与相邻目录前缀匹配。

SDK保持DEFAULT，ConfirmResult.rules为空。checkPermissions和callAsync复用平台决定；startTool在原所有权短事务内最终校验并将PREPARED转RUNNING，遵守现有锁序及owner/fence/租约/实时授权。降档与撤销仅约束未开始动作；无业务工具开放，不生成虚假operation。R4.3才做TOOL_APPROVAL精确恢复。

## HTTP and UI
GET/PATCH tasks/{id}/permissions、GET permission-scopes、POST grants、DELETE grants/{grantId}；修改要求会话/Origin/CSRF/Idempotency-Key/If-Match。缺版本428、陈旧412、幂等内容改变409；终态只读。权限事件持久化与投影同事务。任务工作区固定；草稿变更只影响新任务。目录夹具不作为文件物理隔离证据。

目录候选采用每页50条确认目录及nextCursor（服务端目录UUID）；首屏额外返回根scope，避免100000 inode工作区导致一次性枚举或静默截断。游标仅在当前任务绑定工作区内解析，失效时明确要求刷新。Vue Query infinite query负责分页事实。

## Delivery
先SDK确定性实验→契约/V16→事务/策略→API/Vue→本地PG/MQ/浏览器。轻量2080MiB起步，总额<=2887MiB、宿主余量>=768MiB；不启动业务沙箱、不调用供应商、不修改云端。不兼容运行协议需更新bundle身份并保留旧fixture，不能热恢复旧活动任务。
