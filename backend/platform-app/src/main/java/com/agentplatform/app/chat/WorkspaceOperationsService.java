package com.agentplatform.app.chat;

import com.agentplatform.api.chat.*;
import com.agentplatform.domain.chat.*;
import com.agentplatform.types.WorkspaceOperationData.Page;
import com.agentplatform.types.WorkspaceOperationData.*;
import com.agentplatform.domain.files.AssetStore;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Allowlisted results; private object reads are bounded and never execute an operation. */
public final class WorkspaceOperationsService implements WorkspaceOperationsApi {
    private final ChatRepository repository;
    private final AssetStore assets;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public WorkspaceOperationsService(ChatRepository repository,AssetStore assets){this.repository=repository;this.assets=assets;}
    private static UUID id(String text) {
        try{return UUID.fromString(text);}catch(RuntimeException invalid){throw new ChatFailure(400,"INVALID_RESOURCE_ID");}
    }
    @Override public Page list(ChatApi.Caller caller,String task,String cursor,int limit) {
        return repository.workspaceOperations(new ChatData.Actor(id(caller.userId()),id(caller.spaceId())),id(task),cursor==null?null:id(cursor),limit);
    }
    private static String bounded(String value,int maximum) {
        if(value==null)return null;
        if(value.getBytes(StandardCharsets.UTF_8).length>maximum)throw new ChatFailure(503,"TOOL_RESULT_CORRUPT");return value;
    }
    private static String utf8(byte[] bytes,int start,int length)throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes,start,length)).toString();
    }
    @Override public Result result(ChatApi.Caller caller,String operationId) {
        var actor=new ChatData.Actor(id(caller.userId()),id(caller.spaceId()));
        var record=repository.workspaceOperationResult(actor,id(operationId));
        try {
            var content=JSON.readTree(bounded(record.text(),8192));
            String summary=content.path("summary").isString()?content.path("summary").asString():
                    "workspace_read".equals(record.toolName())&&content.path("text").isString()?content.path("text").asString():
                    switch(record.toolName()) {
                        case "workspace_list" -> "工作区目录结果已保存";
                        case "workspace_write" -> "SUCCEEDED".equals(record.status())?"文件版本已封存":"文件写入未完成";
                        case "workspace_read" -> "文件读取未完成";
                        default -> "执行结果已保存";
                    };
            String stdout=content.path("stdout").isString()?content.path("stdout").asString():null;
            String stderr=content.path("stderr").isString()?content.path("stderr").asString():null;
            if(record.objectKey()!=null) {
                if(record.bytes()==null||record.bytes()<0||record.bytes()>65536||record.stdoutBytes()==null||record.stderrBytes()==null
                        ||record.stdoutBytes()<0||record.stderrBytes()<0||record.stdoutBytes().longValue()+record.stderrBytes()!=record.bytes()
                        ||record.sha256()==null||!record.sha256().matches("[a-f0-9]{64}"))throw new IOException("Invalid result identity");
                try(var object=assets.open(record.objectKey(),null,null)) {
                    if(object.bytes()!=record.bytes()||object.totalBytes()!=record.bytes())throw new IOException("Result length mismatch");
                    byte[] bytes=object.stream().readNBytes(65537);
                    if(bytes.length!=record.bytes()||!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(bytes),HexFormat.of().parseHex(record.sha256())))
                        throw new IOException("Result hash mismatch");
                    stdout=utf8(bytes,0,record.stdoutBytes());stderr=utf8(bytes,record.stdoutBytes(),record.stderrBytes());
                }
            }
            stdout=bounded(stdout,65536);stderr=bounded(stderr,65536);
            if((stdout==null?0:stdout.getBytes(StandardCharsets.UTF_8).length)+(long)(stderr==null?0:stderr.getBytes(StandardCharsets.UTF_8).length)>65536)
                throw new IOException("Combined result bound");
            var files=new ArrayList<Artifact>();
            // Result JSON is an observation, never the authority for downloadable files.
            files.addAll(repository.workspaceOperationArtifacts(actor,record.operationId()));
            if(files.size()>16)throw new IOException("Artifact count bound");
            if(!record.equals(repository.workspaceOperationResult(actor,record.operationId())))throw new IOException("Result changed");
            String code=content.path("code").isString()?content.path("code").asString():null;
            if(code!=null&&!code.matches("[A-Z][A-Z0-9_]{1,95}"))code="TOOL_EXECUTION_FAILED";
            return new Result(record.operationId(),record.taskId(),record.status(),bounded(summary,8192),
                    content.path("exitCode").isIntegralNumber()?content.path("exitCode").asLong():null,stdout,stderr,
                    content.path("truncated").asBoolean(false),code,List.copyOf(files));
        }catch(ChatFailure rejected){throw rejected;}
        catch(Exception invalid){throw new ChatFailure(503,"TOOL_RESULT_UNAVAILABLE");}
    }
}
