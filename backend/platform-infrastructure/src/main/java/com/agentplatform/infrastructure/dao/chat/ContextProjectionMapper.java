package com.agentplatform.infrastructure.dao.chat;

import com.agentplatform.domain.chat.ChatData.Actor;
import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.infrastructure.dao.po.ContextProjectionRows.ProjectionRow;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ContextProjectionMapper {
    Integer lockExecutionSlot(@Param("lease") Lease lease);
    Integer lockConversationLease(@Param("lease") Lease lease);
    Integer lockTask(@Param("lease") Lease lease);
    Integer lockDispatchAttempt(@Param("lease") Lease lease);
    Integer taskVisible(@Param("actor") Actor actor,@Param("task") UUID task);
    ProjectionRow current(@Param("lease") Lease lease);
    ProjectionRow currentForOwner(@Param("actor") Actor actor,@Param("task") UUID task);
    Long lockCurrentEpoch(@Param("lease") Lease lease);
    int insert(@Param("lease") Lease lease,@Param("epoch") long epoch,@Param("bundleHash") String bundleHash,
               @Param("watermark") String watermark,@Param("sourceHash") String sourceHash,@Param("summary") String summary,
               @Param("summaryHash") String summaryHash,@Param("estimate") long estimate,@Param("policy") String policy);
    int point(@Param("lease") Lease lease,@Param("epoch") long epoch,@Param("expected") long expected);
}
