package com.agentplatform.app.workspace;

import com.agentplatform.api.workspace.WorkspaceApi;
import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.WorkspaceData.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.UUID;

public final class WorkspaceApplicationService implements WorkspaceApi {
    private final WorkspaceRepository repository;
    public WorkspaceApplicationService(WorkspaceRepository repository) { this.repository = repository; }
    public static UUID id(String value) {
        try { UUID id = UUID.fromString(value); if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException(); return id; }
        catch (RuntimeException invalid) { throw new WorkspaceFailure(400,"INVALID_WORKSPACE_IDENTITY"); }
    }
    private static Actor actor(Caller caller) { return new Actor(id(caller.userId()),id(caller.spaceId())); }
    private static View view(Workspace w) {
        return new View(w.id().toString(),w.spaceId().toString(),w.name(),w.state(),w.environmentState(),w.quotaBytes(),w.quotaInodes(),
                w.version(),w.operationId().toString(),w.errorCode(),w.createdAt().toString(),false,false);
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @Override public View create(Caller caller,String requestId,String name) {
        if (name==null) throw new WorkspaceFailure(400,"INVALID_WORKSPACE_NAME");
        name=Normalizer.normalize(name.strip(),Normalizer.Form.NFC);
        int length=name.codePointCount(0,name.length());
        if (length<1 || length>80 || name.codePoints().anyMatch(Character::isISOControl)) throw new WorkspaceFailure(400,"INVALID_WORKSPACE_NAME");
        return view(repository.create(actor(caller),id(requestId),hash("CREATE\n"+name),name));
    }
    @Override public View get(Caller caller,String id) { return view(repository.get(actor(caller),id(id))); }
    @Override public Page list(Caller caller,String cursor,int limit) {
        if (limit<1 || limit>100) throw new WorkspaceFailure(400,"INVALID_PAGE_SIZE");
        var rows=repository.list(actor(caller),cursor==null?null:id(cursor),limit+1);
        boolean more=rows.size()>limit;var items=rows.stream().limit(limit).map(WorkspaceApplicationService::view).toList();
        return new Page(items,more?items.getLast().id():null);
    }
    @Override public View retry(Caller caller,String workspace,String requestId,long version) {
        if (version<1) throw new WorkspaceFailure(400,"INVALID_VERSION");
        return view(repository.retry(actor(caller),id(workspace),id(requestId),hash("RETRY\n"+workspace+"\n"+version),version));
    }
}
