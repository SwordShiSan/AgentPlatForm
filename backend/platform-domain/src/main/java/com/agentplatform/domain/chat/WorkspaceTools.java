package com.agentplatform.domain.chat;

import com.agentplatform.types.harness.HarnessData.ToolCategory;
import com.agentplatform.types.harness.HarnessData.ToolDefinition;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Frozen production metadata. All identities and physical authority are resolved by the server. */
public final class WorkspaceTools {
    public static final String VERSION = "workspace-business-tools-v1";
    public static final int ARGUMENT_BYTES = 65536;
    public static final long OUTPUT_BYTES = 100L * 1024 * 1024;
    private WorkspaceTools() {}
    private static void require(boolean condition, String code) { if (!condition) throw new IllegalArgumentException(code); }
    private static String text(String value, int max) {
        require(value != null && value.indexOf('\0') < 0 && value.getBytes(StandardCharsets.UTF_8).length <= max, "TOOL_TEXT_LIMIT");
        return value;
    }
    private static String path(String value, boolean root) {
        String normalized = RiskPolicy.path(value);
        require(root || !normalized.isEmpty(), "TOOL_FILE_PATH_REQUIRED");
        return normalized;
    }
    private static String id(String value) {
        require(value != null && UUID.fromString(value).toString().equals(value), "INVALID_RESOURCE_ID");
        return value;
    }
    public record ListFiles(String parent, String cursor, int limit) {
        public ListFiles { parent=path(parent,true); if(cursor!=null)text(cursor,8192);require(limit>=1&&limit<=100,"INVALID_FILE_PAGE"); }
    }
    public record ReadFile(String fileId, String versionId, long offsetCodePoints, int maxCodePoints) {
        public ReadFile { fileId=id(fileId);versionId=id(versionId);require(offsetCodePoints>=0&&maxCodePoints>=1&&maxCodePoints<=4096,"INVALID_FILE_RANGE"); }
    }
    public record WriteFile(String mode, String path, String text, String fileId, String baseVersionId, Long revision) {
        public WriteFile {
            path=WorkspaceTools.path(path,false);text=WorkspaceTools.text(text,32768);
            require("CREATE".equals(mode)||"REPLACE".equals(mode),"INVALID_FILE_MODE");
            if("CREATE".equals(mode))require(fileId==null&&baseVersionId==null&&revision==null,"INVALID_CREATE_TARGET");
            else {fileId=id(fileId);baseVersionId=id(baseVersionId);require(revision!=null&&revision>0,"VERSION_REQUIRED");}
        }
    }
    public record OutputFile(String path, long maxBytes) {
        public OutputFile {path=WorkspaceTools.path(path,false);require(maxBytes>=0&&maxBytes<=OUTPUT_BYTES,"OUTPUT_BYTES_LIMIT");}
    }
    public record Shell(String command, String cwd, int timeoutSeconds, List<OutputFile> outputs) {
        public Shell {
            command=text(command,32768);require(!command.isBlank(),"COMMAND_REQUIRED");cwd=path(cwd,true);
            require(timeoutSeconds>=1&&timeoutSeconds<=120,"COMMAND_TIMEOUT_LIMIT");
            require(outputs!=null&&outputs.size()<=16&&outputs.stream().noneMatch(Objects::isNull),"OUTPUT_COUNT_LIMIT");outputs=List.copyOf(outputs);
            require(outputs.stream().map(OutputFile::path).distinct().count()==outputs.size(),"DUPLICATE_OUTPUT_PATH");
            require(outputs.stream().mapToLong(OutputFile::maxBytes).sum()<=OUTPUT_BYTES,"OUTPUT_BYTES_LIMIT");
        }
    }
    private static Map<String,Object> string(int max) {return Map.of("type","string","maxLength",max);}
    private static Map<String,Object> nullable(String type) {return Map.of("type",List.of(type,"null"));}
    private static Map<String,Object> cwd() {return Map.of("type","string","maxLength",4096,"description","Relative directory inside the bound workspace. Use the empty string for the workspace root. Absolute paths and dot or dot-dot path segments are invalid.");}
    private static Map<String,Object> object(Map<String,Object> properties) {
        return Map.of("type","object","properties",properties,"required",properties.keySet().stream().sorted().toList(),"additionalProperties",false);
    }
    public static final List<ToolDefinition> DEFINITIONS = List.of(
        new ToolDefinition("workspace_list","List confirmed entries in the task's fixed workspace. File state and immutable version IDs are authoritative; no shell is started.",ToolCategory.READ_ONLY,
            object(Map.of("parent",string(4096),"cursor",nullable("string"),"limit",Map.of("type","integer","minimum",1,"maximum",100))),ListFiles.class,null),
        new ToolDefinition("workspace_read","Read an explicit immutable file version as bounded UTF-8 text. Continue with the returned cursor; never infer unsealed current contents.",ToolCategory.READ_ONLY,
            object(Map.of("fileId",string(36),"versionId",string(36),"offsetCodePoints",Map.of("type","integer","minimum",0),"maxCodePoints",Map.of("type","integer","minimum",1,"maximum",4096))),ReadFile.class,null),
        new ToolDefinition("workspace_write","Create a new UTF-8 file or explicitly replace an exact file/version/revision. Maximum32KiB text; conflicts do not overwrite. This seals an immutable version.",ToolCategory.BUSINESS,
            object(Map.of("mode",Map.of("type","string","enum",List.of("CREATE","REPLACE")),"path",string(4096),"text",string(32768),"fileId",nullable("string"),"baseVersionId",nullable("string"),"revision",nullable("integer"))),WriteFile.class,null),
        new ToolDefinition("workspace_shell","Run a noninteractive /bin/sh -c command inside the isolated networkless sandbox. It can change or delete ANY file in the bound workspace. RISK_BASED requires approval; FULL may auto-run. The whole sandbox stops after each call; only workspace files persist. Declare required output files and byte limits for version sealing.",ToolCategory.BUSINESS,
            object(Map.of("command",string(32768),"cwd",cwd(),"timeoutSeconds",Map.of("type","integer","minimum",1,"maximum",120),"outputs",Map.of("type","array","maxItems",16,"items",object(Map.of("path",string(4096),"maxBytes",Map.of("type","integer","minimum",0,"maximum",OUTPUT_BYTES)))))),Shell.class,null));
    public static final Set<String> NAMES=Set.of("workspace_list","workspace_read","workspace_write","workspace_shell");
    public static boolean registered(ToolDefinition definition) {return DEFINITIONS.contains(definition);}
}
