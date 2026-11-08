package com.agentplatform.app.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentplatform.domain.knowledge.KnowledgeData.Actor;
import com.agentplatform.domain.knowledge.KnowledgeData.Memory;
import com.agentplatform.domain.knowledge.KnowledgeRepository;
import com.agentplatform.domain.knowledge.MemoryRepository;
import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.domain.harness.HarnessPorts.EvidenceReference;
import com.agentplatform.types.harness.HarnessData.Scope;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class TaskContextEvidenceServiceTest {
    @Test void recallsAndRevalidatesPersonalAndBoundWorkspaceMemoryOnly() {
        UUID user=UUID.randomUUID(),space=UUID.randomUUID(),workspace=UUID.randomUUID();
        Actor actor=new Actor(user,space);
        var personal=memory(UUID.randomUUID(),null,"PERSONAL","personal-note");
        var scoped=memory(UUID.randomUUID(),workspace,"WORKSPACE","workspace-note");
        var memories=mock(MemoryRepository.class);
        var scope=new Scope(space.toString(),user.toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),1,false,workspace.toString());
        when(memories.recall(eq(actor),eq(workspace),eq(10))).thenReturn(List.of(personal,scoped));
        when(memories.recall(eq(actor),eq(workspace),eq(100))).thenReturn(List.of(personal,scoped));

        var service=new TaskContextEvidenceService(memories,mock(KnowledgeRepository.class),null);
        var references=service.recall(scope).toCompletableFuture().join().references();

        assertEquals(List.of("PERSONAL_MEMORY","WORKSPACE_MEMORY"),references.stream().map(r->r.sourceKind()).toList());
        service.validate(scope,references);
        verify(memories).recall(actor,workspace,10);
        verify(memories).recall(actor,workspace,100);
    }

    @Test void legacyUnboundTaskCannotRecallWorkspaceMemory() {
        UUID user=UUID.randomUUID(),space=UUID.randomUUID();
        Actor actor=new Actor(user,space);
        var personal=memory(UUID.randomUUID(),null,"PERSONAL","personal-note");
        var memories=mock(MemoryRepository.class);
        var scope=new Scope(space.toString(),user.toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),1,false);
        when(memories.recall(eq(actor),eq(null),eq(10))).thenReturn(List.of(personal));

        var references=new TaskContextEvidenceService(memories,mock(KnowledgeRepository.class),null)
                .recall(scope).toCompletableFuture().join().references();

        assertEquals(List.of("PERSONAL_MEMORY"),references.stream().map(r->r.sourceKind()).toList());
        verify(memories).recall(actor,null,10);
    }

    @Test void rejectsMemoryRevokedBetweenRecallAndModelAdmission() {
        UUID user=UUID.randomUUID(),space=UUID.randomUUID();
        Actor actor=new Actor(user,space);
        var active=memory(UUID.randomUUID(),null,"PERSONAL","revoked-before-model");
        var memories=mock(MemoryRepository.class);
        var scope=new Scope(space.toString(),user.toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),1,false);
        when(memories.recall(eq(actor),eq(null),eq(10))).thenReturn(List.of(active));
        when(memories.recall(eq(actor),eq(null),eq(100))).thenReturn(List.of());

        var service=new TaskContextEvidenceService(memories,mock(KnowledgeRepository.class),null);
        var recalled=service.recall(scope).toCompletableFuture().join().references();
        assertEquals(1,recalled.size());

        ChatFailure revoked=assertThrows(ChatFailure.class,()->service.validate(scope,recalled));
        assertEquals("CONTEXT_SOURCE_REVOKED",revoked.code());
        verify(memories).recall(actor,null,100);
    }

    @Test void rejectsKnowledgeDocumentRevokedBetweenRetrievalAndModelAdmission() {
        UUID user=UUID.randomUUID(),space=UUID.randomUUID(),document=UUID.randomUUID();
        Actor actor=new Actor(user,space);
        var memories=mock(MemoryRepository.class);
        var knowledge=mock(KnowledgeRepository.class);
        var scope=new Scope(space.toString(),user.toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),1,false);
        when(knowledge.document(actor,document)).thenReturn(null);
        var reference=new EvidenceReference("KNOWLEDGE",document.toString(),UUID.randomUUID().toString(),"a".repeat(64),
                "{\"kind\":\"TEXT_OFFSET\",\"start\":0,\"end\":12}","stale source text",false);

        ChatFailure revoked=assertThrows(ChatFailure.class,()->new TaskContextEvidenceService(memories,knowledge,null)
                .validate(scope,List.of(reference)));

        assertEquals("CONTEXT_SOURCE_REVOKED",revoked.code());
    }

    private static Memory memory(UUID id,UUID workspace,String scope,String topic) {
        return new Memory(id,scope,workspace,"FACT",topic,"This is a valid memory reference.","ACTIVE",1,
                Instant.now().plusSeconds(3600),Instant.now());
    }
}
