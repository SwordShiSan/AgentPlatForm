# R1.4 整合验收、贯通诊断与HTTP云端发行

批准基线main795daa5，R1.3 VERIFIED。唯一change r1-4-integration-release，step/r1.4-integration-release，从同步phase/r1-identity-chat开始。Plan与五类OpenSpec strict通过后实施。

## 交付顺序
1. 请求trace从服务端生成并经task/outbox/attempt/call关联；FailureRecorder记录稳定分类，新增GET /api/v1/tasks/{id}/diagnostics，实时身份/space检查，公开投影不含内部异常、正文、key。Vue故障卡显示部分输出、建议和可复制诊断号；UNKNOWN不可盲重试，SSE断线不改变task状态。
2. 审核所有R1权限入口及撤权、账号禁用/改密、key替换撤销、模型禁用后的执行收敛；MQ唯一执行路径、confirm/ACK、generation/fence/60秒租约10秒续租、单槽和预算原子性。旧owner确认停止再对账；UNKNOWN不重放，不宣称R2完整恢复。UD-T01/02/05只验个人空间、稳定ID及开户幂等，未交付文件/Skill/上传子项保留后续阶段。
3. 移除20次/1CNY验收硬阻断，保留历史meter累计13次、49152micros未知预留及5474micros估计消耗，不清零。供应商远程额度为验收边界，尽量少调用，额度耗尽立即停，不换key绕过。区别余额/额度不足、429限流及其他错误。业务budget_account及物理调用预留/UNKNOWN约束不变。
4. HTTP为本次及后续部署基线，HTTPS保留暂不实现，不检查/修改DNS。云端显式HTTP profile，Cookie HttpOnly/SameSite=Lax/Secure=false，精确Origin为既有域名及IP，CSRF和权限保留。SSE网关禁缓冲并匹配120秒task时限；指标/依赖不公开。同步冲突文档的当前结论，历史记录保留。
5. 本地Maven verify/DDD/Enforcer、Vue类型/单测/build、契约生成无差异/非法fixture/链接/strict；真实PG全新及V4升级/R0兼容、MQ故障/事务回滚/幂等/权限/fence/预算/快照；SDK旧状态/Jackson隔离，provider确定性故障；浏览器375/768/1440及200%缩放/键盘/主题/草稿/取消/权限/断线。真实Flash/Pro、PLATFORM/USER、多轮必要闭环最少调用。失败/未运行如实记录。
6. 固定源码、配置、linux/amd64不可变镜像和包哈希，本地通过后step→phase→main两级PR，Actions关闭、不伪造检查、不强推。此时仅R1.4 VERIFIED、R1 4/4未发行。
7. 云端只读盘点R0身份/策略/卷/资源和回滚。现有3655MiB/4核/40GiB，根盘>=5GiB安全余量，容器合计<=2887MiB、宿主留>=768MiB；从已验证2016MiB起按需调整，单执行/哈希槽、不借swap扩容，构建和运行串行。gVisor/XFS配置不变，聊天不启动sandbox；第三方源码不重建。
8. 本地构建/导出同镜像双端hash，云端不构建。停止R0写入，PG/对象/工作区/MQ definitions/密钥版本一致备份；既有新平台DB加法迁移，不迁本地测试数据。必要新增V5，不改V1–V4。运维创建一个管理员和一个普通账号、无默认密码，密码保护文件交付；空历史，平台key受保护输入加密DB，主密钥独立存放。
9. HTTP域名Host及IP真实身份/聊天/SSE/隔离/指标封闭验收；至少30分钟单槽任务和依赖故障观察，采样极值不称绝对峰值。停止写入一致备份、原栈停止，独立临时工程串行恢复核对身份/配置/密文解密/对象和工作区hash/配额/readiness，恢复工程不调用真实模型。
10. 演练停止R1回滚R0及兼容健康，通过后同一R1候选重新上线；失败恢复R0、不启动旧业务平台、不回退main。云端门禁全PASS再production-r1-<sha>，R1 RELEASED，未通过不打tag。归档并更新台账/风险/交接；本地和云端保护证据>=30天，正文另行打包记录位置/hash，不以git推送冒充正文同步。R2未启动。

## Implementation decisions
Use an infrastructure request context UUID, servlet filter and JDBC persisted task trace. Historical tasks receive an additive deterministic trace identity; existing migrations are immutable. Failure records contain only stable codes/classifications and nullable existing task/attempt/call references. Persist in the same transaction as terminal task changes; HTTP failures use structured sanitized logs if DB unavailable. Diagnostics reads are scoped through the existing repository task authorization. No public admin diagnostic bypass.
The real-provider meter continues cumulative updates without fixed number/cost predicates. Provider failure classification stops quota-exhausted runs; business reservations remain unchanged. No automatic UNKNOWN replay. Cloud HTTP profile reuses existing identity/chat beans with explicit origin/cookie configuration, not a global development bypass. Default undeployed configuration remains closed.

## Public HTTP candidate failure and repair2026-10-17
First cloud candidate e6c40bf failed browser first-send before creating a conversation: public HTTP lacks crypto.randomUUID, unlike trusted localhost. R0 rollback completed and public HTTP/readiness passed12services; no R1 production tag created. The original accepted local package and failed cloud browser log remain history. Same approved HTTP plan/change applies; no HTTPS or dependency/source upgrade.
Repair is a project-owned UUIDv4 helper using HTTP-compatible crypto.getRandomValues, applied to submit/cancel; deterministic capability-absence/browser tests plus actual public-HTTP capability check are required. Backend c37bec9 and its121/26/52/PG3/provider/real4/resource evidence remain unchanged; rebuild only gateway, run frontend/types/build/unit/browser and contracts/strict, then repeat two-level PR before deploying the corrected immutable gateway. Do not repeat real four-turn local calls or reset metering. Final cloud30min/restore/rollback still NOT_RUN.
References: https://developer.mozilla.org/en-US/docs/Web/API/Crypto/getRandomValues and https://developer.mozilla.org/en-US/docs/Web/API/Crypto/randomUUID . Export identity correction: Docker Desktop local image IDs are OCI index digests; cloud uses config IDs. Preserve index→amd64 manifest→config→rootfs proof instead of equating them.
