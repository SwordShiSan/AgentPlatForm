package com.agentplatform.infrastructure.dao.workspace;

import com.agentplatform.domain.workspace.EnvironmentUseData.Command;
import com.agentplatform.domain.workspace.FileHelperData.*;
import com.agentplatform.infrastructure.dao.po.FileHelperRows.*;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FileHelperMapper {
    Helper find(@Param("id") UUID id);
    Helper lock(@Param("id") UUID id);
    void insert(@Param("command") Command command, @Param("runner") UUID runner, @Param("intent") Intent intent, @Param("hash") String hash);
    int created(@Param("id") UUID id, @Param("container") String container);
    int startRequested(@Param("id") UUID id);
    int started(@Param("id") UUID id, @Param("physical") Physical physical);
    int recordExec(@Param("id") UUID id, @Param("physical") Physical physical);
    Stop stop(@Param("id") UUID id);
    boolean fresh(@Param("stop") Stopped stop);
    void insertStop(@Param("id") UUID id, @Param("observer") UUID observer, @Param("prior") UUID prior,
            @Param("stop") Stopped stop, @Param("containerId") String containerId, @Param("startedAt") String startedAt,
            @Param("execId") String execId, @Param("text") String text, @Param("hash") String hash);
}
