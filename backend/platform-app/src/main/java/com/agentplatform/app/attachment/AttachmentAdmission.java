package com.agentplatform.app.attachment;

import com.agentplatform.app.files.AuthorizedAssetStream;
import com.agentplatform.domain.attachment.*;
import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.files.AssetStore;
import com.agentplatform.domain.files.FileData.Actor;
import com.agentplatform.infrastructure.dao.files.TaskAttachmentMapper;
import com.agentplatform.infrastructure.harness.agentscope.AgentScopeContextAdmission;
import com.agentplatform.app.chat.ContextProjectionService;
import com.agentplatform.types.AttachmentReference;
import com.agentplatform.types.harness.HarnessData.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Reject oversized assembled input before accepting a task, with all transport outside transactions. */
public final class AttachmentAdmission {
    private static final System.Logger LOGGER=System.getLogger(AttachmentAdmission.class.getName());
    private final TaskAttachmentMapper mapper;private final TransactionTemplate transactions;
    private final ChatRepository chat;private final AssetStore assets;private final ContextPolicy policy;private final boolean toolsEnabled;
    private final java.util.concurrent.Semaphore assemblySlot=new java.util.concurrent.Semaphore(1);
    public AttachmentAdmission(TaskAttachmentMapper mapper,TransactionTemplate transactions,ChatRepository chat,AssetStore assets,ContextPolicy policy,boolean toolsEnabled){this.mapper=mapper;this.transactions=transactions;this.chat=chat;this.assets=assets;this.policy=policy;this.toolsEnabled=toolsEnabled;}
    public void requireFits(Actor actor,UUID conversation,String text,boolean plan,boolean workspace,List<AttachmentReference> references){
        if(!assemblySlot.tryAcquire())throw new ChatFailure(429,"CONTEXT_ASSEMBLY_BUSY");
        try{assemble(actor,conversation,text,plan,workspace,references);}finally{assemblySlot.release();}
    }
    private void assemble(Actor actor,UUID conversation,String text,boolean plan,boolean workspace,List<AttachmentReference> references){
        var messages=new ArrayList<Message>();boolean tools=toolsEnabled&&workspace;
        messages.add(Message.text(Role.SYSTEM,ChatInstructions.current(plan,tools)));
        if(conversation!=null){
            var snapshot=chat.snapshot(new com.agentplatform.domain.chat.ChatData.Actor(actor.userId(),actor.spaceId()),conversation);
            var successful=snapshot.tasks().stream().filter(t->"SUCCEEDED".equals(t.status())).map(com.agentplatform.domain.chat.ChatData.Task::id).collect(java.util.stream.Collectors.toSet());
            for(var message:snapshot.messages())if(successful.contains(message.taskId())&&"COMPLETED".equals(message.status())){
                StringBuilder content=new StringBuilder();
                for(var block:message.blocks())if("TEXT".equals(block.get("type")))content.append((String)block.get("text"));
                else if("FILE_REFERENCE".equals(block.get("type")))content.append(AttachmentContext.marker(UUID.fromString((String)block.get("extractionId")),(String)block.get("textSha256")));
                messages.add(Message.text(Role.valueOf(message.role()),content.toString()));
            }
        }
        var sources=transactions.execute(status->{
            var result=new LinkedHashMap<UUID,AttachmentSource>();
            if(conversation!=null){if(!mapper.inputAuthorized(actor,conversation,null))throw new ChatFailure(403,"ATTACHMENT_AUTHORIZATION_REVOKED");for(var source:mapper.inputSources(actor.spaceId(),conversation,null))result.put(source.extractionId(),source);}
            for(var reference:references){var source=mapper.source(actor,reference);if(source==null)throw new ChatFailure(422,"ATTACHMENT_NOT_READY_OR_UNAUTHORIZED");result.put(source.extractionId(),source);}
            return List.copyOf(result.values());
        });
        StringBuilder current=new StringBuilder(text==null?"":text);
        if(sources.stream().mapToLong(AttachmentSource::textBytes).sum()>policy.stateBytes())throw new ChatFailure(422,"CONTEXT_REQUIRED_TOO_LARGE");
        for(var reference:references){var source=sources.stream().filter(s->s.extractionId().toString().equals(reference.extractionId())).findFirst().orElseThrow();current.append(AttachmentContext.marker(source.extractionId(),source.textSha256()));}
        messages.add(Message.text(Role.USER,current.toString()));
        var contents=new HashMap<UUID,String>();
        for(var source:sources){
            if(source.textBytes()<1||source.textBytes()>2097152)throw new ChatFailure(422,"ATTACHMENT_TEXT_TOO_LARGE");
            try(var content=assets.open(source.textObjectKey(),null,null)){
                if(content.bytes()!=source.textBytes()||content.totalBytes()!=source.textBytes())throw new ChatFailure(409,"ATTACHMENT_TEXT_IDENTITY_MISMATCH");
                var stream=new AuthorizedAssetStream(content.stream(),source.textBytes(),source.textSha256(),()->{});
                byte[] bytes=stream.readNBytes(Math.toIntExact(source.textBytes()+1));if(bytes.length!=source.textBytes()||stream.read()!=-1)throw new IOException("Extracted text integrity failure");
                contents.put(source.extractionId(),StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString());
            }catch(IOException failure){var rejected=new ChatFailure(503,"ATTACHMENT_TEXT_UNAVAILABLE");rejected.initCause(failure);throw rejected;}
        }
        List<ToolDefinition> definitions=plan?(tools?PlanControls.workspaceDefinitions():PlanControls.DEFINITIONS):tools?new ArrayList<>(WorkspaceTools.DEFINITIONS):new ArrayList<>();
        if(!plan&&tools)definitions.add(PlanControls.DEFINITIONS.stream().filter(d->"platform_ask_user".equals(d.name())).findFirst().orElseThrow());
        List<Message> assembled=AttachmentContext.materialize(messages,sources,contents);
        long beforeProjection=AgentScopeContextAdmission.estimate(assembled,definitions);
        try{
            if(policy.compactionEnabled())assembled=ContextProjectionService.preview(assembled,definitions,policy);
            AgentScopeContextAdmission.requireFits(assembled,definitions,policy);
        }catch(ChatFailure failure){
            if("CONTEXT_REQUIRED_TOO_LARGE".equals(failure.code()))LOGGER.log(System.Logger.Level.WARNING,
                    "Context admission refused: policy={0}, compaction={1}, estimateBefore={2}, inputLimit={3}",
                    policy.version(),policy.compactionEnabled(),beforeProjection,policy.inputLimit());
            throw failure;
        }
    }
}
