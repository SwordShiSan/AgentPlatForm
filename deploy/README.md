# Deployment

当前不含可执行Compose或部署脚本。R0建立agentplatform-env与agentplatform-app两个独立Compose project，R9验收发布。设计见[部署方案](../../项目文档/06-AgentScope重构专项/07-交付与验收/03-本地与云端部署.md)。不复用旧平台库/卷/入口，不在本次部署。新站TLS是未来门禁，旧平台仅HTTP80且保持不变。本地容器默认restart:no。
