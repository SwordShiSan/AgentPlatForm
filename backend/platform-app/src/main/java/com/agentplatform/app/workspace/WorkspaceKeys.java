package com.agentplatform.app.workspace;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

final class WorkspaceKeys {
    private WorkspaceKeys() {}
    static RSAKey read(Path path,String kid,boolean privateKey) {
        try {
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>16384)throw new IllegalArgumentException();
            RSAKey key=JWK.parseFromPEMEncodedObjects(Files.readString(path,StandardCharsets.US_ASCII)).toRSAKey();
            if(key.isPrivate()!=privateKey)throw new IllegalArgumentException();
            return new RSAKey.Builder(key).keyID(kid).build();
        }catch(Exception rejected){throw new IllegalStateException("Cannot load pinned workspace signing key");}
    }
}
