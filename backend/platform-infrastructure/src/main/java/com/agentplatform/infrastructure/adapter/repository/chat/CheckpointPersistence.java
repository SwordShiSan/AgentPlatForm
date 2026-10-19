package com.agentplatform.infrastructure.adapter.repository.chat;

import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.types.Uuid7;
import com.agentplatform.types.harness.HarnessData;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.agentplatform.infrastructure.dao.chat.ChatMappers;
import com.agentplatform.infrastructure.dao.po.ChatProjections.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Checkpoint persistence use cases over typed MyBatis collaborators. */
final class CheckpointPersistence extends ChatPersistenceSupport {
    CheckpointPersistence(ChatMappers mappers) { super(mappers); }

    public UUID beginTurn(Lease l){return tx(c->MyBatisExecutionLedger.beginTurn(c,l));}
    public void commitRound(Lease l,UUID id,HarnessData.Turn turn,ExecutionLedger.CallResult accepted){tx(c->{MyBatisExecutionLedger.commitRound(c,l,id,turn,accepted);return null;});}
    public void startTool(Lease l,HarnessData.Call call){tx(c->{MyBatisExecutionLedger.startTool(c,l,call);return null;});}
    public void commitTool(Lease l,HarnessData.Call call,HarnessData.Observation result){tx(c->{MyBatisExecutionLedger.commitTool(c,l,call,result);return null;});}
    public HarnessData.StateSlot state(Lease l,String key){return tx(c->MyBatisExecutionLedger.state(c,l,key));}
    public long stateCas(Lease l,String key,long version,byte[] body){return tx(c->MyBatisExecutionLedger.stateCas(c,l,key,version,body));}
    public Set<String> stateKeys(Lease l){return tx(c->MyBatisExecutionLedger.stateKeys(c,l));}
    public void stateDelete(Lease l,String key){tx(c->{MyBatisExecutionLedger.stateDelete(c,l,key);return null;});}
    public UUID sealCheckpoint(Lease l,HarnessData.Bundle bundle){return tx(c->MyBatisExecutionLedger.seal(c,l,bundle));}
    public List<HarnessData.Message> checkpointHistory(Lease l){return tx(c->MyBatisExecutionLedger.history(c,l));}
    public void validateCheckpoint(Lease l,UUID id,HarnessData.Bundle bundle){tx(c->{MyBatisExecutionLedger.checkpoint(c,l,id,bundle);return null;});}
    public void restoreCheckpoint(Lease l,UUID id,HarnessData.Bundle bundle){tx(c->{MyBatisExecutionLedger.restore(c,l,id,bundle);return null;});}
}
