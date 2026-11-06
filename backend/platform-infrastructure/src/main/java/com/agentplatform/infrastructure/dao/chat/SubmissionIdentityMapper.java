package com.agentplatform.infrastructure.dao.chat;

import com.agentplatform.domain.chat.ChatData.Actor;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SubmissionIdentityMapper {
    record Row(String hash, UUID resourceId, int version) {}
    void lock(@Param("lockKey") long lockKey);
    Row find(@Param("actor") Actor actor,@Param("path") String path,@Param("key") UUID key);
    int insert(@Param("actor") Actor actor,@Param("path") String path,@Param("key") UUID key,
               @Param("hash") String hash,@Param("resource") UUID resource);
}
