package com.agentplatform.infrastructure.dao.chat;
import com.agentplatform.infrastructure.dao.po.LedgerRows.*;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
@Mapper
public interface LedgerMapper {
    List<CheckpointRow1> selectCheckpoint1();
    List<ModelTurnRow23> selectModelTurn23(@Param("slotTask") UUID slotTask);
    int updateToolOperation149(@Param("slotTask") UUID slotTask);
    List<CheckpointRow172> selectCheckpoint172(@Param("id") UUID id);
    List<CheckpointRow173> selectCheckpoint173(@Param("id") UUID id, @Param("spaceId") UUID spaceId, @Param("taskId") UUID taskId);
    List<ToolOperationRow175> selectToolOperation175(@Param("turnId") UUID turnId);
    List<ModelTurnRow176> selectModelTurn176(@Param("taskId") UUID taskId);
    List<ToolOperationRow177> selectToolOperation177(@Param("taskId") UUID taskId);
    List<ModelTurnRow178> selectModelTurn178(@Param("taskId") UUID taskId);
    List<StateSlotRow179> selectStateSlot179(@Param("spaceId") UUID spaceId, @Param("userId") UUID userId, @Param("conversationId") UUID conversationId, @Param("prefix") String prefix);
    List<StateSlotRow180> updateStateSlot180(@Param("payload") String payload, @Param("spaceId") UUID spaceId, @Param("userId") UUID userId, @Param("conversationId") UUID conversationId, @Param("stateKey") String stateKey, @Param("version") long version);
    List<StateSlotRow181> insertStateSlot181(@Param("spaceId") UUID spaceId, @Param("userId") UUID userId, @Param("conversationId") UUID conversationId, @Param("stateKey") String stateKey, @Param("payload") String payload);
    List<StateSlotRow182> selectStateSlot182(@Param("spaceId") UUID spaceId, @Param("userId") UUID userId, @Param("conversationId") UUID conversationId, @Param("stateKey") String stateKey);
    List<ToolOperationRow183> selectToolOperation183(@Param("spaceId") UUID spaceId, @Param("taskId") UUID taskId, @Param("attemptId") UUID attemptId, @Param("fence") long fence, @Param("id") String id, @Param("contentHash") String contentHash);
    List<ModelTurnRow185> selectModelTurn185(@Param("id") UUID id, @Param("spaceId") UUID spaceId, @Param("taskId") UUID taskId, @Param("attemptId") UUID attemptId, @Param("fence") long fence);
    List<ModelTurnRow187> selectModelTurn187(@Param("attemptId") UUID attemptId);
    int insertModelTurn188(@Param("id") UUID id, @Param("spaceId") UUID spaceId, @Param("taskId") UUID taskId, @Param("attemptId") UUID attemptId, @Param("fence") long fence, @Param("input") String input, @Param("contentHash") String contentHash, @Param("attemptId2") UUID attemptId2);
    int insertToolOperation189(@Param("id") UUID id, @Param("spaceId") UUID spaceId, @Param("taskId") UUID taskId, @Param("attemptId") UUID attemptId, @Param("id2") UUID id2, @Param("fence") long fence, @Param("id3") String id3, @Param("canonicalJson") String canonicalJson, @Param("contentHash") String contentHash);
    int updateModelTurn190(@Param("turnId") UUID turnId, @Param("body") String body, @Param("fingerprint") String fingerprint, @Param("id") UUID id, @Param("id2") UUID id2);
    int updateToolOperation191(@Param("spaceId") UUID spaceId, @Param("taskId") UUID taskId, @Param("attemptId") UUID attemptId, @Param("fence") long fence, @Param("id") String id, @Param("contentHash") String contentHash);
    int updateToolOperation192(@Param("body") String body, @Param("digest") String digest, @Param("taskId") UUID taskId, @Param("id") String id, @Param("status") String status);
    int deleteStateSlot193(@Param("spaceId") UUID spaceId, @Param("userId") UUID userId, @Param("conversationId") UUID conversationId, @Param("stateKey") String stateKey);
    int insertCheckpoint194(@Param("id") UUID id, @Param("spaceId") UUID spaceId, @Param("taskId") UUID taskId, @Param("attemptId") UUID attemptId, @Param("fence") long fence, @Param("BUNDLE") UUID BUNDLE, @Param("hash") String hash, @Param("manifest") String manifest, @Param("contentHash") String contentHash, @Param("canonical") String canonical, @Param("contentHash2") String contentHash2, @Param("watermark") long watermark, @Param("state") String state, @Param("contentHash3") String contentHash3);
}
