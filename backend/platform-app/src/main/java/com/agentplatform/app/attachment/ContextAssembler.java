package com.agentplatform.app.attachment;

import com.agentplatform.app.files.AuthorizedAssetStream;
import com.agentplatform.domain.attachment.*;
import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.domain.files.AssetStore;
import com.agentplatform.types.harness.HarnessData.Message;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** One attempt's bounded immutable extraction cache. No storage I/O occurs inside a DB transaction. */
public final class ContextAssembler {
    private final ChatRepository repository;private final AssetStore assets;private final Lease lease;
    private final Map<UUID,String> texts=new HashMap<>();
    public ContextAssembler(ChatRepository repository,AssetStore assets,Lease lease){this.repository=repository;this.assets=assets;this.lease=lease;}
    public Map<UUID,String> proof(){return Map.copyOf(texts);}
    public List<Message> resolve(List<Message> messages){
        var sources=repository.attachmentSources(lease);
        if(!AttachmentContext.unresolved(messages))return messages;
        if(sources.isEmpty()||assets==null)throw new ChatFailure(409,"ATTACHMENT_SOURCE_UNAVAILABLE");
        for(var source:sources)if(!texts.containsKey(source.extractionId())){
            if(source.textBytes()<1||source.textBytes()>2097152)throw new ChatFailure(422,"ATTACHMENT_TEXT_TOO_LARGE");
            try(var content=assets.open(source.textObjectKey(),null,null)){
                if(content.bytes()!=source.textBytes()||content.totalBytes()!=source.textBytes())throw new ChatFailure(409,"ATTACHMENT_TEXT_IDENTITY_MISMATCH");
                var checked=new AuthorizedAssetStream(content.stream(),source.textBytes(),source.textSha256(),()->repository.attachmentSources(lease));
                // The 2MiB extracted-text bound is independent from the 100MiB original upload stream.
                byte[] bytes=checked.readNBytes(Math.toIntExact(source.textBytes()+1));
                if(bytes.length!=source.textBytes()||checked.read()!=-1)throw new ChatFailure(409,"ATTACHMENT_TEXT_IDENTITY_MISMATCH");
                var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
                texts.put(source.extractionId(),decoder.decode(ByteBuffer.wrap(bytes)).toString());
            }catch(IOException invalid){var failure=new ChatFailure(503,"ATTACHMENT_TEXT_UNAVAILABLE");failure.initCause(invalid);throw failure;}
        }
        return AttachmentContext.materialize(messages,sources,texts);
    }
}
