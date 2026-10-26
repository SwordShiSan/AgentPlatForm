package com.agentplatform.infrastructure.dao.chat;

import com.agentplatform.domain.chat.ChatData.Actor;
import com.agentplatform.infrastructure.dao.po.PermissionRows.*;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PermissionMapper {
    PermissionRow permission(@Param("space") UUID space, @Param("task") UUID task);
    boolean workspace(@Param("actor") Actor actor, @Param("id") UUID id);
    List<DirectoryRow> directories(@Param("space") UUID space, @Param("workspace") UUID workspace, @Param("after") String after);
    DirectoryRow directory(@Param("space") UUID space, @Param("workspace") UUID workspace, @Param("id") UUID id);
    int bind(@Param("actor") Actor actor, @Param("task") UUID task, @Param("workspace") UUID workspace, @Param("root") UUID root, @Param("mode") String mode);
    int initialize(@Param("space") UUID space, @Param("task") UUID task, @Param("mode") String mode);
    int mode(@Param("task") UUID task, @Param("mode") String mode, @Param("version") long version);
    int advance(@Param("task") UUID task, @Param("version") long version);
    List<GrantRow> grants(@Param("space") UUID space, @Param("task") UUID task);
    int insertGrant(@Param("id") UUID id, @Param("p") PermissionRow p, @Param("scope") UUID scope, @Param("path") String path, @Param("caps") String caps, @Param("policy") String policy);
    int revoke(@Param("space") UUID space, @Param("task") UUID task, @Param("grant") UUID grant);
    CommandRow command(@Param("actor") Actor actor, @Param("task") UUID task, @Param("key") UUID key);
    int remember(@Param("actor") Actor actor, @Param("task") UUID task, @Param("key") UUID key, @Param("hash") String hash, @Param("response") String response);
    int audit(@Param("id") UUID id, @Param("p") PermissionRow p, @Param("attempt") UUID attempt, @Param("operation") UUID operation, @Param("call") String call, @Param("decision") String decision, @Param("reason") String reason, @Param("hash") String hash, @Param("trace") UUID trace);
    UUID operation(@Param("task") UUID task, @Param("attempt") UUID attempt, @Param("call") String call);
}
