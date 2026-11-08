package com.agentplatform.app.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.agentplatform.domain.chat.ChatData.Actor;
import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.domain.chat.ContextPolicy;
import com.agentplatform.domain.chat.ContextProjectionStore;
import com.agentplatform.domain.chat.ChatRuntime;
import com.agentplatform.infrastructure.harness.agentscope.AgentScopeContextAdmission;
import com.agentplatform.types.harness.HarnessData.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

final class ContextProjectionServiceTest {
    @Test void compactsOnlyAssistantProseAndKeepsUserInputsAndWholeToolPairs() {
        var store=new MemoryStore();var service=new ContextProjectionService(store,JsonMapper.builder().build());
        String large="analysis result ".repeat(35_000);
        var call=new Call("call-1","workspace_read",Map.of("path","notes.md","versionId","version-1"),true);
        var history=new ArrayList<Message>(List.of(Message.text(Role.SYSTEM,"Fixed task policy"),Message.text(Role.USER,"Keep this exact user goal and its constraints."),
                Message.text(Role.ASSISTANT,large),new Message(Role.ASSISTANT,"",List.of(call),List.of()),
                new Message(Role.TOOL,"",List.of(),List.of(new Observation("call-1","workspace_read","complete tool result",OperationStatus.SUCCEEDED))),
                Message.text(Role.USER,"Continue from the committed result.")));
        for(int i=0;i<10;i++)history.add(Message.text(Role.ASSISTANT,"A later complete response "+i));
        var lease=new Lease(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,
                UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID(),1);
        var projection=service.project(lease,ChatRuntime.compactionBundle(false),history,List.of(),ContextPolicy.compaction(262_144,4_096));
        assertTrue(store.commits>0);
        assertEquals("Keep this exact user goal and its constraints.",projection.stream().filter(m->m.role()==Role.USER).findFirst().orElseThrow().text());
        assertTrue(projection.stream().anyMatch(m->m.role()==Role.ASSISTANT&&m.calls().equals(List.of(call))));
        assertTrue(projection.stream().anyMatch(m->m.role()==Role.TOOL&&m.observations().size()==1));
        assertTrue(projection.stream().anyMatch(m->m.role()==Role.SYSTEM&&m.text().contains("middle omitted in the derived summary")));
        assertEquals(16,history.size(),"canonical history is not mutated");
        assertTrue(projection.size()<history.size(),"the derived projection omits eligible historical assistant prose");
    }

    @Test void refusesWhenRequiredUserInputAloneCannotFit() {
        var service=new ContextProjectionService(new MemoryStore(),JsonMapper.builder().build());
        var lease=new Lease(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,
                UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID(),1);
        var history=List.of(Message.text(Role.SYSTEM,"Fixed task policy"),Message.text(Role.USER,"required ".repeat(150_000)));
        var failure=assertThrows(ChatFailure.class,()->service.project(lease,ChatRuntime.compactionBundle(false),history,List.of(),ContextPolicy.compaction(65_536,4_096)));
        assertEquals("CONTEXT_REQUIRED_TOO_LARGE",failure.code());
    }

    @Test void admissionPreviewAllowsCompressibleHistoryWithoutChangingCanonicalMessages() {
        var policy=ContextPolicy.compaction(65_536,4_096);
        String large="historical assistant prose ".repeat(35_000);
        var canonical=new ArrayList<Message>(List.of(Message.text(Role.SYSTEM,"Fixed task policy"),
                Message.text(Role.USER,"The complete user requirement must stay verbatim."),
                Message.text(Role.ASSISTANT,large),
                Message.text(Role.USER,"Continue with the next safe step.")));
        for(int i=0;i<8;i++)canonical.add(Message.text(Role.ASSISTANT,"Recent complete assistant response "+i));
        var preview=ContextProjectionService.preview(canonical,List.of(),policy);
        assertTrue(AgentScopeContextAdmission.estimate(preview,List.of())<=policy.inputLimit());
        assertTrue(preview.stream().noneMatch(message->message.text().equals(large)));
        assertEquals(large,canonical.get(2).text(),"admission projection must not mutate canonical history");
        assertEquals("The complete user requirement must stay verbatim.",canonical.get(1).text());
        assertTrue(preview.stream().anyMatch(message->message.role()==Role.SYSTEM&&message.text().contains("middle omitted")));
    }

    @Test void deduplicatingRepeatedRepliesDoesNotPromoteOldLargeAssistantTextIntoRecentWindow() {
        var policy=ContextPolicy.compaction(65_536,4_096);
        String large="historical assistant prose ".repeat(35_000);
        var canonical=new ArrayList<Message>(List.of(Message.text(Role.SYSTEM,"Fixed task policy"),
                Message.text(Role.USER,"Preserve the first user request."),Message.text(Role.ASSISTANT,large),
                Message.text(Role.USER,"Preserve the second user request.")));
        for(int i=0;i<4;i++){
            canonical.add(Message.text(Role.USER,"Preserve user request "+i));
            canonical.add(Message.text(Role.ASSISTANT,"Repeated deterministic assistant reply."));
        }

        var preview=ContextProjectionService.preview(canonical,List.of(),policy);

        assertTrue(AgentScopeContextAdmission.estimate(preview,List.of())<=policy.inputLimit());
        assertTrue(preview.stream().noneMatch(message->message.text().equals(large)));
        assertEquals(6,preview.stream().filter(message->message.role()==Role.USER).count());
        assertTrue(preview.stream().anyMatch(message->message.role()==Role.SYSTEM&&message.text().contains("middle omitted")));
        assertEquals(large,canonical.get(2).text(),"previewing must not mutate canonical history");
    }

    private static final class MemoryStore implements ContextProjectionStore {
        int commits; Projection current;
        public Optional<Projection> current(Lease lease,String bundle,String watermark,String source){return Optional.empty();}
        public Projection saveIfWatermarkCurrent(Lease lease,Bundle bundle,String watermark,String source,String hash,List<Message> messages,long estimate,String policy){
            commits++;current=new Projection(commits,bundle.hash(),watermark,source,hash,messages,estimate,policy,Instant.now());return current;
        }
        public Summary describe(Actor actor,UUID task){return null;}
    }
}
