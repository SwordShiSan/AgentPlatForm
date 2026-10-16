package com.agentplatform.infrastructure.chat;

import com.agentplatform.domain.chat.ChatData.Actor;
import com.agentplatform.domain.chat.ChatFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class ChatCursor {
    private final byte[] key;
    public ChatCursor(byte[] key){if(key.length!=32)throw new IllegalArgumentException("Cursor key length");this.key=key.clone();}
    private byte[] sign(String value){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException(e);}}
    public String encode(Actor a,UUID id){String body=a.userId()+"|"+a.spaceId()+"|"+id;return Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(StandardCharsets.UTF_8))+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(sign(body));}
    public UUID decode(Actor a,String value){if(value==null)return null;try{if(value.length()>512)throw new IllegalArgumentException();String[] parts=value.split("\\.");if(parts.length!=2)throw new IllegalArgumentException();String body=new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8);if(!MessageDigest.isEqual(sign(body),Base64.getUrlDecoder().decode(parts[1])))throw new IllegalArgumentException();String[] ids=body.split("\\|");if(ids.length!=3||!a.userId().toString().equals(ids[0])||!a.spaceId().toString().equals(ids[1]))throw new IllegalArgumentException();return UUID.fromString(ids[2]);}catch(Exception invalid){throw new ChatFailure(400,"INVALID_CURSOR");}}
}
