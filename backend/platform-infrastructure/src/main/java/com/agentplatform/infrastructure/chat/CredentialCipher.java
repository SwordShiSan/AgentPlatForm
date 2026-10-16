package com.agentplatform.infrastructure.chat;

import com.agentplatform.domain.chat.ChatData.Credential;
import com.agentplatform.domain.chat.ChatFailure;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Master key is externally supplied; Java GCM ciphertext includes the 128-bit authentication tag. */
public final class CredentialCipher {
    private final byte[] master;
    public CredentialCipher(byte[] master){if(master.length!=32)throw new IllegalArgumentException("Master key must contain32 bytes");this.master=master.clone();}
    private static byte[] aad(Credential value){return (value.scope()+"|"+value.id()+"|"+value.spaceId()+"|"+value.ownerId()+"|"+value.keyVersion()).getBytes(StandardCharsets.UTF_8);}
    public Credential encrypt(UUID id,String scope,UUID space,UUID owner,String value){
        byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);
        var metadata=new Credential(id,scope,space,owner,new byte[0],nonce,1,1);
        try{var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(master,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(aad(metadata));return new Credential(id,scope,space,owner,cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)),nonce,1,1);}
        catch(Exception e){throw new ChatFailure(503,"CREDENTIAL_ENCRYPTION_FAILED");}
    }
    public String decrypt(Credential value){
        try{if(value.keyVersion()!=1)throw new IllegalArgumentException();var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(master,"AES"),new GCMParameterSpec(128,value.nonce()));cipher.updateAAD(aad(value));return new String(cipher.doFinal(value.ciphertext()),StandardCharsets.UTF_8);}
        catch(Exception e){throw new ChatFailure(403,"CREDENTIAL_DECRYPTION_REJECTED");}
    }
}
