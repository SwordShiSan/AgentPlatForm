package com.agentplatform.infrastructure.dao.knowledge;

import com.agentplatform.domain.knowledge.KnowledgeData.Actor;
import com.agentplatform.domain.knowledge.KnowledgeData.Memory;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MemoryMapper {
    record MemoryRequest(String action, String requestHash, UUID memoryId, long resultRevision) {}
    record Target(String scopeKind, UUID workspaceId, String topicKey, UUID id) {}
    record InsertInput(Actor actor, UUID id, String scope, UUID workspace, String category, String topic, String content, String contentHash) {}
    boolean authorized(@Param("actor") Actor actor);
    boolean workspaceOwned(@Param("actor") Actor actor, @Param("workspace") UUID workspace);
    MemoryRequest request(@Param("actor") Actor actor, @Param("request") UUID request);
    int insertRequest(@Param("actor") Actor actor, @Param("request") UUID request, @Param("action") String action,
                      @Param("hash") String hash, @Param("memory") UUID memory, @Param("revision") long revision);
    int insert(@Param("input") InsertInput input);
    Memory memory(@Param("actor") Actor actor, @Param("id") UUID id);
    Memory lockMemory(@Param("actor") Actor actor, @Param("id") UUID id);
    List<Memory> list(@Param("actor") Actor actor, @Param("status") String status, @Param("workspace") UUID workspace);
    List<Memory> recall(@Param("actor") Actor actor, @Param("workspace") UUID workspace, @Param("limit") int limit);
    int expire(@Param("actor") Actor actor);
    int supersedeTopic(@Param("actor") Actor actor, @Param("target") Target target);
    Memory activeTopic(@Param("actor") Actor actor, @Param("target") Target target);
    int activate(@Param("actor") Actor actor, @Param("id") UUID id, @Param("revision") long revision);
    int transition(@Param("actor") Actor actor, @Param("id") UUID id, @Param("revision") long revision,
                   @Param("from") String from, @Param("to") String to);
    int edit(@Param("actor") Actor actor, @Param("id") UUID id, @Param("revision") long revision, @Param("category") String category,
             @Param("topic") String topic, @Param("content") String content, @Param("hash") String hash);
    int audit(@Param("id") UUID id, @Param("actor") Actor actor, @Param("memory") UUID memory, @Param("action") String action,
              @Param("revision") long revision, @Param("hash") String hash);
}
