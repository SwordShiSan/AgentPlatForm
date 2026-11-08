package com.agentplatform.infrastructure.adapter.repository.chat;

import com.agentplatform.domain.chat.ChatData.Actor;
import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.domain.chat.ContextProjectionStore;
import com.agentplatform.infrastructure.dao.chat.ContextProjectionMapper;
import com.agentplatform.infrastructure.dao.po.ContextProjectionRows.ProjectionRow;
import com.agentplatform.types.harness.HarnessData.Bundle;
import com.agentplatform.types.harness.HarnessData.Message;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** MyBatis/Spring transactional adapter for immutable, lease-fenced context projections. */
public final class MyBatisContextProjectionStore implements ContextProjectionStore {
    private final ContextProjectionMapper mapper; private final TransactionTemplate transactions; private final JsonMapper json;
    public MyBatisContextProjectionStore(ContextProjectionMapper mapper,TransactionTemplate transactions,JsonMapper json){this.mapper=mapper;this.transactions=transactions;this.json=json;}
    @Override public Optional<Projection> current(Lease lease,String bundleHash,String watermark,String sourceHash){return transactions.execute(status->{
        lockOwner(lease);var row=mapper.current(lease);if(row==null||!row.bundleHash().equals(bundleHash)||!watermarkMatches(row.watermarkJson(),watermark)||!row.sourceManifestSha256().equals(sourceHash))return Optional.empty();
        return Optional.of(project(row));
    });}
    @Override public Projection saveIfWatermarkCurrent(Lease lease,Bundle bundle,String watermark,String sourceHash,String summaryHash,List<Message> messages,long estimate,String policy){return transactions.execute(status->{
        lockOwner(lease);var row=mapper.current(lease);
        if(row!=null&&row.bundleHash().equals(bundle.hash())&&watermarkMatches(row.watermarkJson(),watermark)&&row.sourceManifestSha256().equals(sourceHash))return project(row);
        String encoded=write(messages);if(encoded.getBytes(StandardCharsets.UTF_8).length>2*1024*1024)throw new ChatFailure(422,"CONTEXT_PROJECTION_TOO_LARGE");
        String actualHash=hash(encoded);if(!actualHash.equals(summaryHash))throw new ChatFailure(409,"CONTEXT_PROJECTION_HASH_MISMATCH");
        Long current=mapper.lockCurrentEpoch(lease);long expected=current==null?0:current;long next=expected+1;
        mapper.insert(lease,next,bundle.hash(),watermark,sourceHash,encoded,summaryHash,estimate,policy);
        if(mapper.point(lease,next,expected)!=1)throw new ChatFailure(409,"CONTEXT_PROJECTION_CONFLICT");
        return project(mapper.current(lease));
    });}
    @Override public Summary describe(Actor actor,UUID task){if(mapper.taskVisible(actor,task)==null)throw new ChatFailure(404,"TASK_NOT_FOUND");var row=mapper.currentForOwner(actor,task);if(row==null)return null;
        return new Summary(task,row.epoch(),row.bundleHash(),row.sourceManifestSha256(),row.summarySha256(),row.estimatedInputTokens(),row.policyVersion(),row.createdAt());}
    private void lockOwner(Lease lease){
        if(mapper.lockExecutionSlot(lease)==null||mapper.lockConversationLease(lease)==null||mapper.lockTask(lease)==null||mapper.lockDispatchAttempt(lease)==null)
            throw new ChatFailure(409,"EXECUTION_FENCE_LOST");
    }
    private Projection project(ProjectionRow row){try{return new Projection(row.epoch(),row.bundleHash(),row.watermarkJson(),row.sourceManifestSha256(),row.summarySha256(),List.of(json.readValue(row.summary(),Message[].class)),row.estimatedInputTokens(),row.policyVersion(),row.createdAt());}
        catch(RuntimeException invalid){throw new ChatFailure(409,"CONTEXT_PROJECTION_HASH_MISMATCH");}}
    private boolean watermarkMatches(String stored,String hash){try{Map<?,?> value=json.readValue(stored,Map.class);return hash.equals(value.get("ledgerHash"));}catch(RuntimeException invalid){return false;}}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception failure){throw new ChatFailure(503,"CONTEXT_PROJECTION_SERIALIZATION_FAILED");}}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
}
