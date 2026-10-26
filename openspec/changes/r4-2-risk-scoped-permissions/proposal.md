# Why
R4.1已交付持久Plan与QUESTION，但任务尚无工作区绑定、实时权限修订或目录授权。需在业务工具开放前建立可撤销、可审计且由平台执行账本强制复核的权限基础。

# What Changes
- 新增V16：任务资源绑定、实时权限、目录grant、幂等变更及决策审计，不修改V1–V15或历史配置哈希。
- 提交支持options.workspaceId和RISK_BASED/FULL；增加permissions、permission-scopes、grants接口及Vue权限面板。
- 复用SDK DEFAULT/checkPermissions及平台startTool事务；FULL不映射SDK bypass、不越ACL/Plan/强制策略/未开放能力。
- 修正分期：R4.2策略和范围授权，R4.3精确一次批准恢复，R4.4业务工具贯通与发行。

# Impact
影响contracts、六模块及Vue。只在独立本地工程验收，真实供应商调用0；云端保持R3，Actions关闭，本地沙箱与DEFERRED云方案不变。
