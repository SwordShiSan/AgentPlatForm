package com.agentplatform.infrastructure.workspace;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapturedFileStreamTest {
    private final UUID operation=UUID.randomUUID();
    private CapturedFileStream.Descriptor descriptor(byte[] content){return new CapturedFileStream.Descriptor(operation,content.length,RunnerSignatures.sha256(content));}
    private byte[] framed(byte[] content){var wire=new ByteArrayOutputStream();wire.writeBytes(content);wire.writeBytes(HexFormat.of().parseHex(RunnerSignatures.sha256(content)));return wire.toByteArray();}
    @Test void emptyAndUnicodePayloadStripTrailerAndConfirmExitBeforeEof()throws Exception {
        for(byte[] content:List.of(new byte[0],"666😀中文".getBytes(StandardCharsets.UTF_8))) {
            var exit=new AtomicBoolean();try(var stream=new CapturedFileStream(new ByteArrayInputStream(framed(content)),descriptor(content),()->{},()->exit.set(true))) {
                assertArrayEquals(content,stream.readAllBytes());assertTrue(stream.verified());assertTrue(exit.get());assertEquals(-1,stream.read());
            }
        }
    }
    @Test void shortCorruptAndExtraPayloadNeverBecomeCommittedEof()throws Exception {
        byte[] content="expected".getBytes(StandardCharsets.UTF_8);byte[] correct=framed(content);byte[] altered=correct.clone();altered[0]='x';
        for(byte[] wire:List.of(Arrays.copyOf(correct,correct.length-1),altered,Arrays.copyOf(correct,correct.length+1))) {
            var exit=new AtomicBoolean();try(var stream=new CapturedFileStream(new ByteArrayInputStream(wire),descriptor(content),()->{},()->exit.set(true))) {
                assertThrows(IOException.class,stream::readAllBytes);assertFalse(stream.verified());assertFalse(exit.get());
                assertThrows(IOException.class,stream::read);
            }
        }
    }
    @Test void actualExitAndLiveAuthorityAreRequiredEvenWithAValidDigest()throws Exception {
        byte[] content="666".getBytes(StandardCharsets.UTF_8);
        try(var stream=new CapturedFileStream(new ByteArrayInputStream(framed(content)),descriptor(content),()->{},()->{throw new IllegalStateException("callbacks still active");})) {
            assertThrows(IOException.class,stream::readAllBytes);assertFalse(stream.verified());
        }
        try(var stream=new CapturedFileStream(new ByteArrayInputStream(framed(content)),descriptor(content),()->{throw new IllegalStateException("revoked");},()->{})) {
            assertThrows(IOException.class,stream::read);assertFalse(stream.verified());
        }
    }
    @Test void descriptorIsOperationBoundStrictAndCannotOverdrawReservation()throws Exception {
        String valid="{\"protocol\":\"file-capture-v1\",\"operationId\":\""+operation+"\",\"state\":\"DESCRIBED\",\"bytes\":3,\"sha256\":\""+"a".repeat(64)+"\"}";
        assertEquals(3,CapturedFileStream.descriptor(new ByteArrayInputStream((valid+"\n").getBytes(StandardCharsets.UTF_8)),operation,3).bytes());
        for(String invalid:List.of(valid.replace("3,","4,"),valid.replace("3,","true,"),valid.replace("\"bytes\":3,",""),
                valid.replace("DESCRIBED","UNKNOWN"),valid.replace(operation.toString(),UUID.randomUUID().toString()),valid+"{}",valid.substring(0,valid.length()-1)+",\"endpoint\":\"bad\"}"))
            assertThrows(IOException.class,()->CapturedFileStream.descriptor(new ByteArrayInputStream((invalid+"\n").getBytes(StandardCharsets.UTF_8)),operation,3));
    }
}
