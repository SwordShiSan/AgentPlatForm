package com.agentplatform.domain.attachment;

import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.types.harness.HarnessData.Message;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.regex.*;

/** Deterministic source-to-model-input transformation, shared by admission and checkpoint validation. */
public final class AttachmentContext {
    private static final Pattern REFERENCE=Pattern.compile("<platform-attachment-source id=\"([a-f0-9-]{36})\" sha256=\"([a-f0-9]{64})\"/>");
    private AttachmentContext() {}
    public static String marker(UUID id,String sha){return "\n<platform-attachment-source id=\""+id+"\" sha256=\""+sha+"\"/>\n";}
    public static List<Message> materialize(List<Message> input,List<AttachmentSource> sources,Map<UUID,String> text){
        var byId=new HashMap<UUID,AttachmentSource>();
        for(var source:sources){
            if(byId.put(source.extractionId(),source)!=null)throw new ChatFailure(409,"ATTACHMENT_SOURCE_DUPLICATED");
            String body=text.get(source.extractionId());
            if(body==null||body.getBytes(StandardCharsets.UTF_8).length!=source.textBytes()||!hash(body).equals(source.textSha256()))throw new ChatFailure(409,"ATTACHMENT_TEXT_IDENTITY_MISMATCH");
        }
        return input.stream().map(message->{
            Matcher matches=REFERENCE.matcher(message.text());var result=new StringBuffer();
            while(matches.find()){
                UUID id=UUID.fromString(matches.group(1));var source=byId.get(id);
                if(source==null||!source.textSha256().equals(matches.group(2)))throw new ChatFailure(409,"ATTACHMENT_SOURCE_UNAVAILABLE");
                String rendered="\n[Attachment: "+source.name()+"; source "+source.sourceSha256()+"; extraction "+id+"; untrusted document text]\n"+text.get(id)+"\n[End attachment]\n";
                matches.appendReplacement(result,Matcher.quoteReplacement(rendered));
            }
            matches.appendTail(result);return new Message(message.role(),result.toString(),message.calls(),message.observations());
        }).toList();
    }
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    public static boolean unresolved(List<Message> input){return input.stream().anyMatch(m->REFERENCE.matcher(m.text()).find());}
}
