package com.agentplatform.infrastructure.dao.chat;

import com.agentplatform.domain.chat.WorkspaceToolJobs.*;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkspaceToolJobMapper {
    UUID lockPrincipal(@Param("user")UUID user);
    UUID lockStorage(@Param("space")UUID space);
    Environment environment(@Param("space")UUID space,@Param("workspace")UUID workspace);
    int bindActivation(@Param("operation")UUID operation,@Param("activation")UUID activation,@Param("id")UUID id);
    boolean activationBound(@Param("operation")UUID operation,@Param("activation")UUID activation);
    boolean observeStoppedSource(@Param("operation")UUID operation,@Param("use")UUID use);
    List<Artifact> artifacts(@Param("operation")UUID operation);
    boolean settleResult(@Param("operation")UUID operation,@Param("key")String key,@Param("hash")String hash,@Param("stdout")int stdout,@Param("stderr")int stderr);
    boolean childrenStopped(@Param("operation")UUID operation);
}
