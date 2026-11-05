package com.agentplatform.domain.attachment;

import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.types.harness.HarnessData.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentContextTest {
    private AttachmentSource source(UUID id,String text){return new AttachmentSource(id,"CHAT_ATTACHMENT",null,null,null,UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),AttachmentContext.hash(text),"正文.txt","private-text-key",text.getBytes(StandardCharsets.UTF_8).length);}
    @Test void checkpointMaterializationRequiresTheExactFrozenTextHashAndBytes(){
        UUID id=UUID.randomUUID();String text="中文🙂固定正文";var source=source(id,text);
        var input=List.of(Message.text(Role.USER,"请阅读"+AttachmentContext.marker(id,source.textSha256())));
        var output=AttachmentContext.materialize(input,List.of(source),Map.of(id,text));
        assertTrue(output.getFirst().text().contains(text));assertTrue(output.getFirst().text().contains(id.toString()));
        assertThrows(ChatFailure.class,()->AttachmentContext.materialize(input,List.of(source),Map.of(id,text+"changed")));
        assertThrows(ChatFailure.class,()->AttachmentContext.materialize(input,List.of(source),Map.of()));
    }
    @Test void sourceTextIsNotRecursivelyInterpretedAsAnotherReference(){
        UUID id=UUID.randomUUID();String text=AttachmentContext.marker(UUID.randomUUID(),"b".repeat(64));var source=source(id,text);
        var input=List.of(Message.text(Role.USER,AttachmentContext.marker(id,source.textSha256())));
        var output=AttachmentContext.materialize(input,List.of(source),Map.of(id,text));
        assertTrue(output.getFirst().text().contains(text));
    }
}
