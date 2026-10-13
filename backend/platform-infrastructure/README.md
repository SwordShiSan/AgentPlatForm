# platform-infrastructure

持久化、AgentScope适配、模型、连接器、文件与runner实现；实现domain端口。

R0.2新增固定AgentScope2.0.4的运行工厂、消息codec和scope/CAS状态适配。数据/策略/工具通过纯端口注入，应用默认不创建Agent；生产DB/MQ/S3/runner尚未接入。FileProbeStore仅为test fixture，不能作为生产持久化实现。测试区分真实SDK+脚本模型、loopback供应商协议和真实模型（未运行）。
