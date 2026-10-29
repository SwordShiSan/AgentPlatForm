package com.agentplatform.infrastructure.workspace;

import com.agentplatform.domain.preview.PreviewRunnerPort;
import com.agentplatform.domain.preview.PreviewData.*;
import com.agentplatform.domain.workspace.WorkspaceFailure;
import java.net.URI;
import tools.jackson.databind.json.JsonMapper;

/** Fixed internal origin, bounded signed envelopes. Timeout never reissues a POST. */
public final class SignedPreviewRunnerClient implements PreviewRunnerPort, AutoCloseable {
    public static final String PATH="/internal/previews/v1";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final SignedRunnerHttpClient transport;
    public SignedPreviewRunnerClient(URI base,RunnerSignatures signatures) {
        this.transport=new SignedRunnerHttpClient(base,signatures);
    }
    public static RunnerSignatures.Binding binding(Command c,String method,String path) {
        return new RunnerSignatures.Binding(method,path,c.commandId(),c.previewId(),c.spaceId(),c.workspaceId(),c.generation(),c.fence());
    }
    private Receipt exchange(Command c,boolean query) {
        String path=PATH+(query?"/"+c.previewId():"");byte[] body=query?new byte[0]:JSON.writeValueAsBytes(c);
            var result=transport.exchange(binding(c,query?"GET":"POST",path),binding(c,"RESPONSE",path),body,query,Receipt.class);
            if(result!=null&&(!c.commandId().equals(result.commandId())||!c.previewId().equals(result.previewId())
                    ||c.generation()!=result.generation()||c.fence()!=result.fence()))throw new WorkspaceFailure(503,"PREVIEW_RECEIPT_MISMATCH");
            return result;
    }
    @Override public Receipt render(Command command){return exchange(command,false);}
    @Override public Receipt query(Command command){return exchange(command,true);}
    @Override public void close(){transport.close();}
}
