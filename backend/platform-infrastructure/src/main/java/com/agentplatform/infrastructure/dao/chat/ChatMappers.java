package com.agentplatform.infrastructure.dao.chat;
/** Typed Mapper collaborators, all using the same Spring SqlSessionTemplate. */
public record ChatMappers(LedgerMapper ledger, ConversationMapper conversation, DispatchMapper dispatch, AccountingMapper accounting, ConfigurationMapper configuration, EventMapper event, ControlMapper control, RecoveryMapper recovery, QuotaMapper quota, ExecutionPolicyMapper executionPolicy, PlanMapper plan, PermissionMapper permissions, ApprovalMapper approvals, WorkspaceToolMapper workspaceTools,
        com.agentplatform.infrastructure.dao.files.TaskAttachmentMapper attachments) {
    public ChatMappers(LedgerMapper ledger,ConversationMapper conversation,DispatchMapper dispatch,AccountingMapper accounting,ConfigurationMapper configuration,EventMapper event,ControlMapper control,RecoveryMapper recovery,QuotaMapper quota,ExecutionPolicyMapper executionPolicy,PlanMapper plan,PermissionMapper permissions,ApprovalMapper approvals,WorkspaceToolMapper workspaceTools){this(ledger,conversation,dispatch,accounting,configuration,event,control,recovery,quota,executionPolicy,plan,permissions,approvals,workspaceTools,null);}
}
