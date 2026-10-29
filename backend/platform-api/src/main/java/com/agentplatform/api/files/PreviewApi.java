package com.agentplatform.api.files;

import java.io.InputStream;
import java.util.List;

public interface PreviewApi {
    record Caller(String userId,String spaceId) {}
    record Create(String versionId,String format) {}
    record View(String id,String fileId,String versionId,String state,String format,String mediaType,Long bytes,String sha256,
            Integer pages,String errorCode,boolean retryable,long revision,List<String> allowedActions) {}
    record Content(InputStream stream,long bytes,long totalBytes,Long first,Long last,String sha256,String mediaType) implements AutoCloseable {
        @Override public void close()throws java.io.IOException{stream.close();}
    }
    View create(Caller caller,String fileId,Create body,String requestId);
    View get(Caller caller,String previewId);
    View retry(Caller caller,String previewId,String ifMatch,String requestId);
    Content content(Caller caller,String previewId,String range);
}
