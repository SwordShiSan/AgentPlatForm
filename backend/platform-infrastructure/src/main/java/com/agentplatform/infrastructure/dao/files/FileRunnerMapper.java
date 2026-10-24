package com.agentplatform.infrastructure.dao.files;

import com.agentplatform.domain.files.FileData.*;
import com.agentplatform.infrastructure.dao.po.FileRunnerRows.Physical;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FileRunnerMapper {
    Physical command(@Param("id") UUID commandId);
    int accept(@Param("command") Command command,@Param("runner") UUID runner,@Param("hash") String hash);
    int created(@Param("command") Command command,@Param("runner") UUID runner,@Param("container") String container);
    int requestStart(@Param("command") Command command,@Param("runner") UUID runner);
    int started(@Param("command") Command command,@Param("runner") UUID runner,@Param("time") String time);
    int exec(@Param("command") Command command,@Param("runner") UUID runner,@Param("exec") String exec);
    boolean priorRunnerProof(@Param("command") Command command,@Param("observer") UUID observer);
    void receipt(@Param("command") Command command,@Param("row") Receipt row);
}
