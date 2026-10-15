# Contracts

R0建立 [OpenAPI3.1](openapi.yaml) 和 [事件信封JSON Schema](events/platform-event.schema.json)。实际接口为GET /api/v1/health与GET /api/v1/readiness：前者仅壳层存活，后者只证明基线schema兼容和依赖连接，返回READY/NOT_READY及200/503，不表示模型或任务执行就绪。信封的payload由后续能力细化，R0不发布业务任务事件。HTTP/事件语义以[统一契约索引](../../项目文档/06-AgentScope重构专项/01-统一契约/00-契约索引与字段字典.md)为准。

在frontend运行 `pnpm contracts:generate` 生成client/types，`pnpm contracts:check` 校验schema及生成无diff，`pnpm contracts:test` 校验正常/非法fixture。设置API_BASE_URL可校验真实API健康和Problem响应。不要手改generated文件或新增虚假业务端点。
