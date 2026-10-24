package com.agentplatform.infrastructure.dao.files;

import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper public interface FileTransferMapper {
    int acquire(@Param("owner")UUID owner,@Param("hash")String hash,@Param("role")String role,@Param("process")UUID process);
    int release(@Param("owner")UUID owner);
    boolean released(@Param("owner")UUID owner);
    UUID runnerOwner(@Param("process")UUID process,@Param("hash")String hash);
    int releaseRunner(@Param("owner")UUID owner,@Param("process")UUID process,@Param("hash")String hash,@Param("command")UUID command);
}
