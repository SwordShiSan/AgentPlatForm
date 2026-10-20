package com.agentplatform.infrastructure.dao.chat;

import com.agentplatform.infrastructure.dao.po.ReplayBounds;
import com.agentplatform.infrastructure.dao.po.ConversationRows.PlatformEventRow51;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EventMapper {
    ReplayBounds bounds(@Param("spaceId") UUID spaceId, @Param("conversationId") UUID conversationId);
    List<PlatformEventRow51> page(@Param("spaceId") UUID spaceId, @Param("conversationId") UUID conversationId, @Param("after") long after);
    List<UUID> retentionCandidates();
    int collectExpiredPrefix(@Param("conversationId") UUID conversationId);
}
