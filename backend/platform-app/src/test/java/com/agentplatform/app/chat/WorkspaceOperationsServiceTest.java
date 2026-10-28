package com.agentplatform.app.chat;

import com.agentplatform.api.chat.ChatApi;
import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.files.AssetStore;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceOperationsServiceTest {
    private final ChatRepository repository=mock(ChatRepository.class);
    private final AssetStore assets=mock(AssetStore.class);
    private final ChatData.Actor actor=new ChatData.Actor(UUID.randomUUID(),UUID.randomUUID());
    private final ChatApi.Caller caller=new ChatApi.Caller(actor.userId().toString(),actor.spaceId().toString());
    private final UUID operation=UUID.randomUUID(),task=UUID.randomUUID();
    @Test void largeOutputRequiresExactPrivateObjectHashAndIsReauthorizedAfterRead() {
        byte[] bytes="666😀error".getBytes(StandardCharsets.UTF_8);int stdout="666😀".getBytes(StandardCharsets.UTF_8).length;
        var row=new WorkspaceToolLedger.ResultRecord(operation,task,"workspace_shell","SUCCEEDED","{\"summary\":\"done\",\"exitCode\":0}",null,
                "private-result-key",RunnerSignatures.sha256(bytes),(long)bytes.length,stdout,bytes.length-stdout);
        when(repository.workspaceOperationResult(actor,operation)).thenReturn(row);
        when(assets.open(row.objectKey(),null,null)).thenAnswer(invocation->new AssetStore.Content(new ByteArrayInputStream(bytes),bytes.length,bytes.length));
        var result=new WorkspaceOperationsService(repository,assets).result(caller,operation.toString());
        assertEquals("666😀",result.stdout());assertEquals("error",result.stderr());assertEquals(0L,result.exitCode());
        assertFalse(result.toString().contains("private-result-key"));verify(repository,times(2)).workspaceOperationResult(actor,operation);
        when(repository.workspaceOperationResult(actor,operation)).thenReturn(row).thenThrow(new ChatFailure(403,"ACCESS_REVOKED"));
        assertEquals(403,assertThrows(ChatFailure.class,()->new WorkspaceOperationsService(repository,assets).result(caller,operation.toString())).status());
    }
    @Test void wrongHashAndCombinedOversizeAreNotReturned() {
        byte[] bytes="secret bytes".getBytes(StandardCharsets.UTF_8);
        var row=new WorkspaceToolLedger.ResultRecord(operation,task,"workspace_shell","SUCCEEDED","{}",null,"private","a".repeat(64),(long)bytes.length,bytes.length,0);
        when(repository.workspaceOperationResult(actor,operation)).thenReturn(row);
        when(assets.open(row.objectKey(),null,null)).thenReturn(new AssetStore.Content(new ByteArrayInputStream(bytes),bytes.length,bytes.length));
        assertEquals(503,assertThrows(ChatFailure.class,()->new WorkspaceOperationsService(repository,assets).result(caller,operation.toString())).status());
        clearInvocations(assets);
        var oversized=new WorkspaceToolLedger.ResultRecord(operation,task,"workspace_shell","SUCCEEDED","{}",null,"private","a".repeat(64),65537L,65537,0);
        when(repository.workspaceOperationResult(actor,operation)).thenReturn(oversized);
        assertEquals(503,assertThrows(ChatFailure.class,()->new WorkspaceOperationsService(repository,assets).result(caller,operation.toString())).status());
        verifyNoInteractions(assets);
    }
    @Test void inlineReadProjectsOnlyApprovedFieldsAndPreservesUnicode() {
        var row=new WorkspaceToolLedger.ResultRecord(operation,task,"workspace_read","SUCCEEDED","{\"text\":\"666😀\",\"hostPath\":\"internal-only\",\"token\":\"sentinel-key\"}",null,null,null,null,null,null);
        when(repository.workspaceOperationResult(actor,operation)).thenReturn(row);
        var result=new WorkspaceOperationsService(repository,assets).result(caller,operation.toString());
        assertEquals("666😀",result.summary());assertFalse(result.toString().contains("sentinel-key"));verifyNoInteractions(assets);
    }
    @Test void missingSummaryNeverFallsBackToReflectingThePrivateResultMap() {
        var row=new WorkspaceToolLedger.ResultRecord(operation,task,"workspace_shell","FAILED","{\"hostPath\":\"internal-only\",\"token\":\"sentinel-key\"}",null,null,null,null,null,null);
        when(repository.workspaceOperationResult(actor,operation)).thenReturn(row);
        var result=new WorkspaceOperationsService(repository,assets).result(caller,operation.toString());
        assertFalse(result.toString().contains("sentinel-key"));assertFalse(result.toString().contains("internal-only"));
    }
}
