package com.agentplatform.infrastructure.dao.chat;

import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.infrastructure.dao.po.PlanRows.*;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PlanMapper {
    PlanRow plan(@Param("space") UUID space,@Param("task") UUID task);
    List<RevisionRow> revisions(@Param("plan") UUID plan);
    RevisionRow revision(@Param("id") UUID id);
    int insertPlan(@Param("id") UUID id,@Param("space") UUID space,@Param("task") UUID task);
    int insertRevision(@Param("id") UUID id,@Param("plan") PlanRow plan,@Param("title") String title,@Param("markdown") String markdown,@Param("hash") String hash,@Param("source") String source);
    int advancePlan(@Param("plan") UUID plan,@Param("state") String state,@Param("newRevision") boolean newRevision);
    ControlRow control(@Param("task") UUID task,@Param("call") String call);
    int insertControl(@Param("id") UUID id,@Param("lease") Lease lease,@Param("call") String call,@Param("tool") String tool,@Param("hash") String hash,@Param("arguments") String arguments);
    int bindControl(@Param("id") UUID id,@Param("interaction") UUID interaction);
    int finishControl(@Param("id") UUID id,@Param("result") String result);
    InteractionRow interaction(@Param("space") UUID space,@Param("task") UUID task,@Param("id") UUID id);
    List<InteractionRow> pending(@Param("space") UUID space,@Param("conversation") UUID conversation);
    List<InteractionRow> taskPending(@Param("task") UUID task);
    int insertInteraction(@Param("id") UUID id,@Param("space") UUID space,@Param("task") UUID task,@Param("attempt") UUID attempt,@Param("call") String call,@Param("kind") String kind,@Param("optional") boolean optional,@Param("payload") String payload,@Param("revision") UUID revision,@Param("expires") java.time.Instant expires);
    int closeInteraction(@Param("id") UUID id,@Param("state") String state);
    AnswerRow answer(@Param("interaction") UUID interaction);
    AnswerRow command(@Param("user") UUID user,@Param("command") UUID command);
    int insertAnswer(@Param("id") UUID id,@Param("interaction") InteractionRow interaction,@Param("user") UUID user,@Param("command") UUID command,@Param("hash") String hash,@Param("decision") String decision,@Param("content") String content,@Param("trace") UUID trace);
    int insertResume(@Param("id") UUID id,@Param("interaction") InteractionRow interaction,@Param("checkpoint") UUID checkpoint,@Param("answer") UUID answer,@Param("hash") String hash,@Param("revision") UUID revision,@Param("generation") long generation);
    ResumeRow resume(@Param("attempt") UUID attempt);
    ResumeRow queuedResume(@Param("task") UUID task);
    int claimResume(@Param("id") UUID id,@Param("attempt") UUID attempt);
    int bindResume(@Param("id") UUID id,@Param("lease") Lease lease);
    long attemptCalls(@Param("attempt") UUID attempt);
    int finishResume(@Param("attempt") UUID attempt);
    int waitingTask(@Param("task") UUID task);
    int updatePlanOption(@Param("task") UUID task,@Param("plan") boolean plan);
    int closeTaskInteractions(@Param("task") UUID task,@Param("state") String state);
    UUID expiringTask();
    List<InteractionRow> staleInteractions(@Param("task") UUID task);
    int consumeOptional(@Param("lease") Lease lease);
    record InputRow(UUID answerId,String callId,String contentJson,String answerHash) {}
    List<InputRow> consumedInputs(@Param("task") UUID task);
}
