# Backend

预留 Java21 / Maven 六模块 DDD 工程，R0才建立父POM、wrapper和依赖锁定。模块内按identity/conversation/execution/workspace/capability/knowledge/governance/automation组织。

依赖：types为基础；domain→types；api→types；infrastructure→domain/types；trigger→api/domain/types；app→trigger/infrastructure/domain/api/types。用例接口放api，app实现，trigger只依赖接口，避免app/trigger循环。只有app负责启动装配和事务协调；仅infrastructure接入AgentScope。
