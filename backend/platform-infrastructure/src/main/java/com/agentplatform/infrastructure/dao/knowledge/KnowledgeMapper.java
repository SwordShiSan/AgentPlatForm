package com.agentplatform.infrastructure.dao.knowledge;

import com.agentplatform.domain.knowledge.KnowledgeData.*;
import com.agentplatform.domain.knowledge.KnowledgeData.Actor;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KnowledgeMapper {
    record KnowledgeMutation(String action, String requestHash, UUID resourceId) {}
    record Heavy(String kind, UUID referenceId, Long generation, UUID ownerId, long fence, long revision) {}
    record BuildState(String state, boolean leaseLive) {}
    boolean authorized(@Param("actor") Actor actor);
    boolean workspaceOwned(@Param("actor") Actor actor, @Param("workspace") UUID workspace);
    KnowledgeMutation request(@Param("actor") Actor actor, @Param("request") UUID request);
    int insertRequest(@Param("actor") Actor actor, @Param("request") UUID request, @Param("action") String action,
                      @Param("hash") String hash, @Param("resource") UUID resource);
    Base base(@Param("actor") Actor actor, @Param("id") UUID id);
    List<Base> bases(@Param("actor") Actor actor);
    int insertBase(@Param("actor") Actor actor, @Param("id") UUID id, @Param("name") String name, @Param("normalized") String normalized);
    ExtractedSource extraction(@Param("actor") Actor actor, @Param("base") UUID base, @Param("extraction") UUID extraction);
    int insertDocument(@Param("actor") Actor actor, @Param("id") UUID id, @Param("base") UUID base, @Param("source") ExtractedSource source);
    Document document(@Param("actor") Actor actor, @Param("id") UUID id);
    List<Document> documents(@Param("actor") Actor actor, @Param("base") UUID base);
    int setRebuildState(@Param("actor") Actor actor, @Param("document") UUID document);
    int deleteDocument(@Param("actor") Actor actor, @Param("document") UUID document);
    int insertIndex(@Param("space") UUID space, @Param("document") UUID document, @Param("version") UUID version,
                    @Param("modelHash") String modelHash, @Param("tokenizerHash") String tokenizerHash);
    int insertBuild(@Param("id") UUID id, @Param("space") UUID space, @Param("document") UUID document, @Param("version") UUID version);
    int insertOutbox(@Param("id") UUID id, @Param("space") UUID space, @Param("build") UUID build);
    List<Publication> publications(@Param("owner") UUID owner);
    int markPublished(@Param("item") Publication item, @Param("confirmed") boolean confirmed);
    boolean knownPublication(@Param("item") Publication item);
    boolean knownInbox(@Param("item") Publication item);
    BuildState buildState(@Param("item") Publication item);
    int quarantine(@Param("id") UUID id, @Param("hash") String hash);
    int insertInbox(@Param("item") Publication item);
    Heavy heavy();
    int acquireHeavy(@Param("build") UUID build, @Param("generation") long generation, @Param("owner") UUID owner);
    int releaseHeavy(@Param("command") BuildCommand command);
    Long acquireQueryHeavy(@Param("actor") Actor actor, @Param("query") UUID query, @Param("owner") UUID owner);
    boolean queryHeavyCurrent(@Param("actor") Actor actor, @Param("query") UUID query, @Param("owner") UUID owner, @Param("fence") long fence);
    int releaseQueryHeavy(@Param("query") UUID query, @Param("owner") UUID owner, @Param("fence") long fence);
    int claim(@Param("item") Publication item, @Param("owner") UUID owner, @Param("fence") long fence);
    BuildCommand command(@Param("build") UUID build);
    int insertChunk(@Param("command") BuildCommand command, @Param("chunk") Chunk chunk);
    boolean chunkMatches(@Param("command") BuildCommand command, @Param("chunk") Chunk chunk);
    int progress(@Param("command") BuildCommand command, @Param("ordinal") int ordinal);
    boolean live(@Param("command") BuildCommand command);
    int renew(@Param("command") BuildCommand command);
    int commitIndex(@Param("command") BuildCommand command, @Param("count") int count, @Param("manifest") String manifest);
    int retirePrevious(@Param("command") BuildCommand command);
    int commitDocument(@Param("command") BuildCommand command);
    int completeBuild(@Param("command") BuildCommand command);
    int failIndex(@Param("command") BuildCommand command);
    int failDocument(@Param("command") BuildCommand command);
    int failBuild(@Param("command") BuildCommand command, @Param("error") String error);
    List<Hit> search(@Param("actor") Actor actor, @Param("baseIds") List<UUID> baseIds, @Param("query") String query,
                     @Param("terms") List<String> terms, @Param("vector") String vector, @Param("limit") int limit);
}
