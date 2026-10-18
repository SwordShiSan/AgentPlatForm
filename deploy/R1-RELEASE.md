## 2026-10-18 首页入口修复已上线

当前生产tag `production-r1-24b3c6558d17`，接受main 24b3c6558d17d9bd3d167a9c4c6270f8dda21ff4；仅gateway更新，前端源码 d7707acc045c1954a91f49f196ef8d145207cf8b，config sha256:a926aced9585adfeef570e7da06a2f088cc0ba5c22a0574215835927e3de3468。后端沿用 c37bec9fdb34bced93b460918d80752107e2b3cc / sha256:6853788d51b309e45ff36200a533304431266fb3feaaf121209daa807fffe757，无迁移或账号变更。首页登录后直接聊天，/status公开连接检查。22单测、本地双构建10+5浏览器、公网5浏览器和历史SSE/权限/内部404通过；0新增模型请求，其他容器身份/运行状态不变。已有旧业务容器运行状态为本次前既有，保留且不作为本次已暂停声明。原gateway与部署清单留在云端保护操作目录，可单独回退。详见[脱敏摘要](r1-home-entry-acceptance.json)；最终保护交付收据另存，不把Git推送当正文同步。

# R1 HTTP integration and release

Status: R1.4 VERIFIED; R1 4/4 VERIFIED; R1 RELEASED. Production tag `production-r1-28da397d47ed`; deployment source `28da397d47ed90212362a22110160a234f70c5d3`. R2 NOT_STARTED.

The single change is `r1-4-integration-release`; the canonical R1.4 plan governs all acceptance and release gates. GitHub Actions stay disabled. HTTPS remains reserved and unimplemented by explicit user decision, including subsequent stages. HTTP uses explicit `r1-http` configuration, HttpOnly/SameSite=Lax/non-Secure cookies and exact existing domain/IP Origins with CSRF. No DNS changes or checks.

`compose.r1-http.app.yaml` and `compose.r1-http.env.yaml` overlay the existing two cloud projects. The protected `deploy/r1-enabled` activation marker selects them in `production-stack.sh`; absence retains the R0 lifecycle. R1 starts API/worker/gateway and seven dependencies, never runner/egress/sandbox. Initial resident caps total2272MiB. Temporary operators/builds/restore stacks run serially. All active caps must remain within2887MiB with768MiB host reserve, no swap extension and root disk reserve5GiB.

Run additive Flyway V5 and reviewed `config/r1-runtime-grants.sql` before enabling identity/chat. V1–V4 and runtime schema1 remain unchanged. Default platform profiles remain closed. API may encrypt and insert credentials but cannot SELECT their ciphertext; worker decrypts on each authorized call. Independently generated32-byte master/cursor secrets stay protected outside Git/images. Only operations profiles provision accounts; no anonymous registration/default account.

Acceptance metering is cumulative and no longer hard-limited to20calls/1CNY. Preserve all existing UNKNOWN reservations; business budget accounts remain authoritative. Stop real acceptance when the provider reports exhausted balance/quota; minimize calls and do not rotate keys to evade limits. Official402 means insufficient balance;429 is rate limiting: https://api-docs.deepseek.com/quick_start/error_codes/ . No provider error bodies are logged in the deployed worker profile.

Local mandatory tests and exact-source step→phase→main promotion precede deployment. Transfer immutable accepted images with manifest/archive hashes; no cloud compilation. Save R0 config/images/policies and a consistent cold data/XFS/object/MQ/key backup. Do not import local test accounts/history. Provision one administrator and one ordinary cloud acceptance account with generated protected passwords and an encrypted platform credential.

Cloud gates include HTTP browser/auth/SSE/permissions, minimal real calls, >=30minutes task/dependency observation, independent serial consistent restore with actual quota/readiness/key verification, and R0 rollback followed by the same R1 candidate. Cloud failure restores R0 and keeps main; legacy business containers remain stopped. Create `production-r1-<sha>` only after every mandatory gate passes. Full checkpoint recovery is not delivered until R2.

Diagnostics: `GET /api/v1/tasks/{id}/diagnostics` returns owner-scoped code/classification/trace/action/partial-output facts; UNKNOWN never allows automatic replay. Request trace is server-generated and persisted through task/outbox/attempt/call; HTTP failures are correlated without logging bodies/secrets.

Final evidence and canonical-body delivery hashes are recorded separately at closeout. Local/cloud evidence retention >=30days. Historical failed candidates are retained, not rewritten as PASS.

## Master cold-start correction (release still pending)

The2272MiB candidate passed1800.14s and independent restore, but master64MiB was OOM-killed twice at final cold startup. R0 was restored12services; no R1 tag. R1 master is now128MiB with unchanged GOMEMLIMIT48MiB and official image; total2336MiB<=2887MiB. Existing real Flash/Pro results are reused exactly without new calls; a fresh30min window adds three master cold recreations, then repeats serial backup/restore/R0 rollback/final startup. Earlier passing scopes and failed candidate remain historical.

## Actual release evidence2026-10-17

R1四个小阶段全部VERIFIED，已完成HTTP云端发行；全局8/40小阶段VERIFIED、2/10大阶段RELEASED。生产tag `production-r1-28da397d47ed` 指向接受的部署源码 `28da397d47ed90212362a22110160a234f70c5d3`；API/worker源码c37bec9、UI源码6a4014b，镜像索引→amd64 manifest→云端config ID及归档哈希完整对应。发布后归档记录提交不改变已验收运行产物，不移动生产tag。R2未启动，必须另行Plan/OpenSpec。

云端HTTP真实浏览器/身份/刷新/SSE/双用户及管理员隔离/内部端点封闭PASS；本阶段云端实际物理调用2次，Flash/Pro最小闭环成功，恢复过程0次真实调用。本地历史累计17次、UNKNOWN预留49152micros和估计7434micros保持，云端独立计量不代表清零或覆盖本地历史。估计费用非账单，业务预算继续强制。

修订后真实观察1800.13秒、171次采样，MQ/PG中断readiness503后恢复，另有三次master强制冷重建/Leader健康/零自动重启；复用已持久化匹配的成功模型/usage，本轮新增真实调用0。旧64MiB master候选冷启动两次OOM已保留FAIL，R0恢复后修订master128MiB/GOMEMLIMIT48MiB，不改第三方源码或应用镜像。容器总限额2336MiB<=2887MiB，采样最小宿主可用内存1982.6MiB，容器当前swap0、不借swap扩大额度。宿主已有swap占用趋势单独记录；这些是采样结果，不是绝对峰值或更高并发承诺。根盘始终保留>=5GiB。独立串行恢复通过PG/vector/身份配置/对象与工作区hash、实际XFS配额拒绝/缺挂载拒绝、密文凭据认证解密及readiness；原XFS UUID未改。R1停止→R0恢复12服务/健康→同一R1候选10服务最终恢复PASS；旧业务容器仍停机、数据和原回滚材料保留。

当前及后续采用HTTP，HTTPS保留暂不实现，无DNS检查/修改。精确Origin/CSRF、HttpOnly/SameSite=Lax、显式Secure=false、DB凭据加密/实时权限保持。GitHub Actions禁用，PR保护保留；实现、HTTP及资源修复PR34–39已merged，归档PR与最终main/phase身份见交付receipt。首次公网HTTP randomUUID失败及R0回滚保留历史，修复后Vue22/浏览器15与真实公网HTTP验证通过，不改写原失败。

正文独立包、脱敏证据包、逐文件SHA256和本地/云端保护副本交付位置见AgentPlatForm/evidence/r1.4/r1.4-handoff.json，至少保留至2026-11-17；账号密码单独放保护文件，不进入公开仓库或脱敏包。Git推送不等于正文远端同步。以下记录均保留为历史。
