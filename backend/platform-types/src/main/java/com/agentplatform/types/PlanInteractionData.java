package com.agentplatform.types;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Public plan projections contain no private SDK state or permission grants. */
public final class PlanInteractionData {
    private PlanInteractionData() {}
    public enum Kind { PLAN_APPROVAL, QUESTION, TOOL_APPROVAL }
    public enum Decision { EXECUTE_PLAN, REVISE_PLAN, REJECT_PLAN, DELIVER_PLAN, ANSWER, SKIP, ALLOW_ONCE, DENY }
    public record Revision(UUID id, long revision, String title, String markdown, String source, String hash, Instant createdAt) {}
    public record Plan(UUID taskId, String status, long version, Revision current, List<Revision> revisions) {
        public Plan { revisions=List.copyOf(revisions); }
    }
    public record Option(String id,String label) {}
    /** Redacted approval projection; arguments and framework state remain server-owned. */
    public record ToolApprovalPayload(UUID operationId, String toolName, String summary, String reasonCode,
                                      UUID workspaceId, String target, String targetVersion, String callHash,
                                      boolean stale, List<Decision> allowedActions, long permissionVersion,
                                      String policyVersion) {
        public ToolApprovalPayload { allowedActions=List.copyOf(allowedActions); }
    }
    public record Pending(UUID interactionId, UUID taskId, Kind kind, boolean optional, long version,
                          Instant expiresAt, Revision plan, String question, List<Option> options,
                          ToolApprovalPayload approval) {
        public Pending { options=List.copyOf(options); }
        public Pending(UUID interactionId, UUID taskId, Kind kind, boolean optional, long version,
                       Instant expiresAt, Revision plan, String question, List<Option> options) {
            this(interactionId,taskId,kind,optional,version,expiresAt,plan,question,options,null);
        }
    }
    public record Edit(String title,String markdown) {
        public Edit {
            text(title,1024,true);text(markdown,131072,true);
            if(title.getBytes(StandardCharsets.UTF_8).length+markdown.getBytes(StandardCharsets.UTF_8).length>131072)throw new IllegalArgumentException("PLAN_BYTES_LIMIT");
        }
    }
    public record Answer(long expectedVersion, Decision decision, String text, List<String> optionIds) {
        public Answer {
            if(expectedVersion<1||decision==null)throw new IllegalArgumentException("INTERACTION_ANSWER_INVALID");
            text=text==null?"":text;optionIds=optionIds==null?List.of():List.copyOf(optionIds);
            PlanInteractionData.text(text,32768,false);
            long bytes=text.getBytes(StandardCharsets.UTF_8).length;
            for(String id:optionIds){PlanInteractionData.text(id,128,true);bytes+=id.getBytes(StandardCharsets.UTF_8).length;}
            if(bytes>32768||optionIds.size()>16||optionIds.stream().distinct().count()!=optionIds.size())throw new IllegalArgumentException("INTERACTION_ANSWER_INVALID");
        }
    }
    public record Accepted(UUID taskId,UUID interactionId,String status,long version) {}
    public static void text(String value,int maxBytes,boolean required) {
        if(value==null||(required&&value.isBlank())||value.getBytes(StandardCharsets.UTF_8).length>maxBytes)
            throw new IllegalArgumentException("INTERACTION_TEXT_INVALID");
    }
}
