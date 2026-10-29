package com.agentplatform.api.files;

public interface FileMutationApi {
    record Move(String targetParentId,String name) {}
    record Restore(String sourceVersionId,String deletionOperationId,String targetParentId,String name) {}
    record RequestView(String operationId,String uploadId,String state,FileApi.OperationView operation) {}
    record DeletedFile(String id,String workspaceId,String path,String kind,String versionId,long revision,
            String deletionOperationId,String deletedAt,String retainUntil,boolean canRestore) {}
    record DeletedPage(java.util.List<DeletedFile> items,String nextCursor) {}
    record Preview(long revision,int entries,boolean allConfirmed) {}
    FileApi.OperationView move(FileApi.Caller caller,String file,String request,String ifMatch,Move body);
    FileApi.OperationView delete(FileApi.Caller caller,String file,String request,String ifMatch);
    FileApi.OperationView restore(FileApi.Caller caller,String file,String request,String ifMatch,Restore body);
    RequestView request(FileApi.Caller caller,String file,String request);
    DeletedPage deleted(FileApi.Caller caller,String workspace,String cursor,int limit);
    Preview preview(FileApi.Caller caller,String file);
}
