package com.agentplatform.api.files;

import java.io.InputStream;
import java.io.IOException;
import java.util.List;

public interface FileApi {
    record Caller(String userId, String spaceId) {}
    record CreateUpload(String path, long bytes, String sha256, String fileId, String baseVersionId) {}
    record UploadView(String id, String workspaceId, String fileId, String versionId, String path, long bytes,
            String sha256, String state, String operationId, long revision, String expiresAt, List<String> allowedActions) {}
    record FileView(String id, String workspaceId, String path, String parent, String name, String kind,
            String versionId, String collectionState, long revision, String sha256, Long bytes) {}
    record Page(List<FileView> items, String nextCursor) {}
    record OperationView(String id, String workspaceId, String uploadId, String state, long revision,
            String errorCode, List<String> allowedActions) {}
    record UsageView(long objectLimitBytes, long reservedBytes, long committedBytes, long workspaceQuotaBytes,
            long workspaceQuotaInodes, Long measuredBytes, Long measuredInodes, String measuredAt) {}
    record Download(InputStream stream, long bytes, long totalBytes, Long first, Long last, String sha256,
            String filename, String mime) implements AutoCloseable {
        @Override public void close() throws IOException { stream.close(); }
    }
    UploadView create(Caller caller, String workspace, String requestId, String ifMatch, CreateUpload body);
    UploadView upload(Caller caller, String id);
    UploadView transfer(Caller caller, String id, long contentLength, InputStream content);
    OperationView complete(Caller caller, String id);
    OperationView operation(Caller caller, String id);
    Page list(Caller caller, String workspace, String parent, String cursor, int limit);
    FileView file(Caller caller, String id);
    Download download(Caller caller, String id, String version, String range);
    UsageView usage(Caller caller, String workspace);
}
