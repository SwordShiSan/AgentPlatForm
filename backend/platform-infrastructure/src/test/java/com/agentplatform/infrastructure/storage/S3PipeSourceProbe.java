package com.agentplatform.infrastructure.storage;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.UUID;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import tools.jackson.databind.json.JsonMapper;

/** Fixed100MiB source only. No Docker access; the authorized host forwards this raw stream. */
public final class S3PipeSourceProbe {
    public static void main(String[] args) throws Exception {
        long bytes=100L*1024*1024;var digest=MessageDigest.getInstance("SHA-256");
        byte[] chunk=new byte[65536];java.util.Arrays.fill(chunk,(byte)102);
        for(long i=0;i<bytes/chunk.length;i++)digest.update(chunk);
        String hash=HexFormat.of().formatHex(digest.digest());
        try(var client=S3Clients.create(URI.create(System.getenv("R33_S3_ENDPOINT")),StaticCredentialsProvider.create(
                AwsBasicCredentials.create(System.getenv("R33_S3_ACCESS"),System.getenv("R33_S3_SECRET"))))) {
            var store=new S3AssetStore(client,"r33-stream-probe");
            var header=new LinkedHashMap<String,Object>();header.put("protocol","file-stream-v1");
            header.put("operationId",UUID.randomUUID().toString());header.put("path","from-s3.dat");
            header.put("bytes",bytes);header.put("sha256",hash);header.put("expectedSha256",null);
            try(var object=store.open("probe/104857600",null,null)) {
                System.out.write((JsonMapper.builder().build().writeValueAsString(header)+"\n").getBytes(StandardCharsets.UTF_8));
                long count=0;int read;
                while((read=object.stream().read(chunk))!=-1){System.out.write(chunk,0,read);count+=read;}
                System.out.flush();if(count!=bytes)throw new IllegalStateException("Fixed source length mismatch");
            }
        }
    }
}
