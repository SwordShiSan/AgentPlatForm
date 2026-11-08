## Implementation

- [x] 1. 完成正式计划、ADR、受影响契约和 strict 门禁。
- [x] 2. 实验 SDK MCP、受控出口及只读物化：SDK/出口真实夹具、签名服务重放与授权测试、真实 docker-java/runsc/XFS 挂载均有独立证据；单次签名 HTTP＋数据库＋物理 Docker 组合 canary 未运行，未将分项验证表述为组合端到端。
- [x] 3. V23、API 契约、资产、导入及版本管理；真实 PG／MQ／S3导入和幂等证据见 verification。
- [x] 4. 个人／工作区绑定及生效预览；真实双用户／撤销及页面证据见 verification。
- [x] 5. 连接器、凭据、探测及受保护调用账本；TLS 夹具下受保护调用／UNKNOWN不重放及浏览器验收见 verification。
- [x] 5a. 将 SDK 发现放入受限子进程；超大响应、密钥管道、实际退出和受限环境证据见 verification。
- [x] 6. runner 签名、版本化策略与只读材料；固定签名路由和单次重放测试、实时授权／持久租约测试及真实 runsc/XFS 只读挂载分别通过。完整签名 HTTP＋数据库＋物理容器的单次组合演练未运行，按证据边界记录，不伪称端到端。
- [x] 7. Vue 管理页面与固定一键验收入口；真实浏览器 3 项通过，fresh-db 结果绑定到候选构建。

## Verification and closure

- [x] 8. 工程、迁移、真实依赖、权限、浏览器及180秒观察；独立验收索引见 `evidence/r62/acceptance-index.json`。
- [ ] 9. 两级 PR、归档、台账、Skill 和两份保护交付。
