## Why

R5保存模型和凭据偏好，但任务入口提前填充Plan和权限，工作区覆盖及真实来源无法生效。R6.1交付设置、预览和冻结任务的完整链路。

## What Changes

- 复用个人偏好，增加Plan和权限默认及独立工作区覆盖。
- 用统一解析结果完成预览、附件准入、任务受理和快照。
- Skill版本绑定与真实导入一起移至R6.2；本期不提供假目录。

## Capabilities

### New Capabilities
- `personal-configuration`: 个人与工作区配置的版本化继承和冻结。

### Modified Capabilities

## Impact

V22、配置及任务契约、MyBatis事务、Vue和独立本地验收；云端继续R5。
