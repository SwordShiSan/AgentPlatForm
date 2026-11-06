package com.agentplatform.infrastructure.adapter.repository.chat;

import com.agentplatform.domain.chat.ChatData.Actor;
import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.infrastructure.dao.chat.SubmissionIdentityMapper;
import java.util.UUID;

/** Requires the caller's existing transaction and identity authorization. */
public final class SubmissionIdentityRepository {
    private final SubmissionIdentityMapper mapper;
    public SubmissionIdentityRepository(SubmissionIdentityMapper mapper) { this.mapper=mapper; }

    public UUID lookup(Actor actor,String path,UUID key,String legacyHash,String currentHash) {
        validate(currentHash);
        // This is the same lock key as the existing chat path; old and new writers serialize.
        mapper.lock((long)(actor+path+key).hashCode());
        var row=mapper.find(actor,path,key);
        if(row==null)return null;
        String expected=switch(row.version()) { case 1 -> legacyHash; case 2 -> currentHash; default -> null; };
        if(expected==null||!expected.equals(row.hash()))throw new ChatFailure(409,"IDEMPOTENCY_CONFLICT");
        return row.resourceId();
    }
    public void remember(Actor actor,String path,UUID key,String currentHash,UUID resource) {
        validate(currentHash);
        if(mapper.insert(actor,path,key,currentHash,resource)!=1)throw new ChatFailure(409,"IDEMPOTENCY_CONFLICT");
    }
    private static void validate(String hash) {
        if(hash==null||!hash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid submission identity");
    }
}
