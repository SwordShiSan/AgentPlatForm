# Why
R3 已发行，但当前聊天拒绝 Plan、没有持久用户交互，物理调用4次/SDK8轮与恢复额度限制也不符合用户文本任务需求。需复用唯一 SDK Harness 形成可停止、可跨进程恢复的 Plan/问题闭环。

# What Changes
- 增加 CONTROL 工具、持久计划修订/交互/答案/WAITING_INPUT 和同任务新 attempt 续接；新增 V15，保留旧迁移。
- 增加 Plan/answer API、严格事件、生成客户端及 Vue 交互卡。
- 业务额度改为可配置的用户每日tokens（默认1M、管理员角色不限额但计量），费用默认仅统计，累计活动时间继续控制，移除请求次数限制；真实验收单独<=20次。
- 修正 R4.1–R4.4 分期；云方案继续 DEFERRED、本地沙箱不变。

# Impact
影响 contracts、types/domain/app/infrastructure/trigger/api、前端与唯一正文。保持 Java21/Boot4/SDK2.0.4、MyBatis/AMQP。仅本地验收，不部署 R4、不开放业务工具；现有R3继续在线。
