package com.agentplatform.app.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.agentplatform.api.chat.ChatApi;
import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.domain.chat.ChatRepository;
import com.agentplatform.infrastructure.chat.ChatCursor;
import java.util.*;
import org.junit.jupiter.api.Test;

class ChatProjectionTest {
    @Test void publicBlocksExcludeControlReasoningAndUnknownFields() {
        UUID user=UUID.randomUUID(),space=UUID.randomUUID(),conversation=UUID.randomUUID(),task=UUID.randomUUID(),message=UUID.randomUUID();
        var repository=mock(ChatRepository.class);
        var blocks=List.<Map<String,Object>>of(Map.of("type","TEXT","blockId",message.toString(),"text","hello 🧪","reasoning","private"),Map.of("type","INTERNAL","blockId",message.toString(),"text","control-secret"));
        when(repository.snapshot(new Actor(user,space),conversation)).thenReturn(new Snapshot(conversation,1,List.of(new Message(message,task,"ASSISTANT",blocks,"COMPLETED",1),new Message(UUID.randomUUID(),task,"SYSTEM",blocks,"COMPLETED",1)),List.of()));
        var service=new ChatService(repository,new ChatCursor(new byte[32]));
        var snapshot=service.snapshot(new ChatApi.Caller(user.toString(),space.toString()),conversation.toString());
        assertEquals(1,snapshot.messages().size());assertEquals(List.of(Map.of("type","TEXT","blockId",message.toString(),"text","hello 🧪")),snapshot.messages().getFirst().blocks());
        assertFalse(snapshot.toString().contains("private"));assertFalse(snapshot.toString().contains("control-secret"));
    }
}
