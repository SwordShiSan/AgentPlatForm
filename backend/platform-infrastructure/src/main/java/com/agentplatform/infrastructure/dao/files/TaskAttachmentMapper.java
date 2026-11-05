package com.agentplatform.infrastructure.dao.files;

import com.agentplatform.domain.attachment.AttachmentSource;
import com.agentplatform.domain.files.FileData.Actor;
import com.agentplatform.types.AttachmentReference;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TaskAttachmentMapper {
    int requestLock(@Param("identity")String identity);
    boolean recentFile(@Param("actor")Actor actor,@Param("file")com.agentplatform.types.WorkbenchPreferences.RecentFile file);
    AttachmentSource source(@Param("actor")Actor actor,@Param("ref")AttachmentReference ref);
    String requestHash(@Param("actor")Actor actor,@Param("path")String path,@Param("request")UUID request);
    boolean legacyRequest(@Param("actor")Actor actor,@Param("path")String path,@Param("request")UUID request);
    void remember(@Param("actor")Actor actor,@Param("path")String path,@Param("request")UUID request,@Param("hash")String hash,@Param("task")UUID task);
    int bind(@Param("actor")Actor actor,@Param("task")UUID task,@Param("ordinal")int ordinal,@Param("source")AttachmentSource source);
    int appendBlock(@Param("task")UUID task,@Param("block")String block);
    List<AttachmentSource> inputSources(@Param("space")UUID space,@Param("conversation")UUID conversation,@Param("task")UUID task);
    boolean inputAuthorized(@Param("actor")Actor actor,@Param("conversation")UUID conversation,@Param("task")UUID task);
}
