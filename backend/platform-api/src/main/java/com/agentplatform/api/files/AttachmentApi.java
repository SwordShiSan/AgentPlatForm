package com.agentplatform.api.files;

import java.io.InputStream;
import java.util.List;

public interface AttachmentApi {
    record Caller(String userId,String spaceId) {}
    record Create(String draftId,String filename,long bytes,String sha256) {}
    record Upload(String id,String attachmentId,String status,long bytes,String sha256,long revision,
            String expiresAt,List<String> allowedActions) {}
    record View(String id,String filename,long bytes,String sha256,String status,String conversationId,
            long revision,List<String> allowedActions) {}
    record Content(InputStream stream,long bytes,String sha256,String filename) implements AutoCloseable {
        @Override public void close()throws java.io.IOException{stream.close();}
    }
    record Category(String category,long bytes,long objects) {}
    record Usage(long objectLimitBytes,long usedBytes,long reservedBytes,long retainedBytes,List<Category> categories) {}
    Upload create(Caller caller,String requestId,Create body);
    Upload upload(Caller caller,String id);
    Upload request(Caller caller,String request);
    Upload transfer(Caller caller,String id,long length,InputStream source);
    View complete(Caller caller,String id);
    View get(Caller caller,String id);
    Content content(Caller caller,String id);
    Usage usage(Caller caller);
}
