package com.agentplatform.api.knowledge;

import java.util.List;

public interface KnowledgeApi {
    record Caller(String userId, String spaceId) {}
    record CreateBase(String name) {}
    record AddDocument(String extractionId) {}
    record Search(String query, List<String> baseIds, Integer limit, boolean semanticRequired) {}
    record BaseView(String id, String name, String state, long revision) {}
    record DocumentView(String id, String sourceKind, String fileId, String versionId, String attachmentId,
                        String extractionId, String name, String state, String activeIndexVersion, long revision) {}
    record SearchHit(String documentId, String sourceKind, String versionId, String attachmentId, String name, String text, String location,
                     double score, String sourceSha256) {}
    record SearchView(List<SearchHit> hits, boolean semanticAvailable, String degradationReason) {}
    BaseView createBase(Caller caller, String requestId, CreateBase body);
    List<BaseView> bases(Caller caller);
    List<DocumentView> documents(Caller caller, String baseId);
    DocumentView addDocument(Caller caller, String baseId, String requestId, AddDocument body);
    DocumentView rebuild(Caller caller, String documentId, String requestId);
    void deleteDocument(Caller caller, String documentId, String requestId);
    SearchView search(Caller caller, Search body);
}
