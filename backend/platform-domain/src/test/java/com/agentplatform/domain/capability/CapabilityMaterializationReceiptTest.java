package com.agentplatform.domain.capability;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CapabilityMaterializationReceiptTest {
    private static final String HASH="a".repeat(64);

    @Test void committedRequiresAnExactStoppedPhysicalCanary() {
        var command=UUID.randomUUID();var operation=UUID.randomUUID();var workspace=UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,()->new CapabilityMaterializationReceipt(command,operation,workspace,
                1,1,"COMMITTED",HASH,HASH,1,true,0,"container","sha256:"+HASH,
                Instant.parse("2026-11-07T10:00:00Z"),Instant.parse("2026-11-07T10:00:01Z"),null,1,false));
        var receipt=new CapabilityMaterializationReceipt(command,operation,workspace,1,1,"COMMITTED",HASH,HASH,1,true,0,
                "container","sha256:"+HASH,Instant.parse("2026-11-07T10:00:00Z"),Instant.parse("2026-11-07T10:00:01Z"),null,1,true);
        assertTrue(receipt.stopConfirmed());
    }

    @Test void uncertainStopCannotBeReportedAsFailedOrCommitted() {
        var command=UUID.randomUUID();var operation=UUID.randomUUID();var workspace=UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,()->new CapabilityMaterializationReceipt(command,operation,workspace,
                1,1,"BLOCKED",HASH,HASH,0,false,null,"container","sha256:"+HASH,
                Instant.parse("2026-11-07T10:00:00Z"),null,"CAPABILITY_MATERIALIZATION_STOP_UNCONFIRMED",1,true));
        var blocked=new CapabilityMaterializationReceipt(command,operation,workspace,1,1,"BLOCKED",HASH,HASH,0,false,null,
                "container","sha256:"+HASH,Instant.parse("2026-11-07T10:00:00Z"),null,
                "CAPABILITY_MATERIALIZATION_STOP_UNCONFIRMED",1,false);
        assertTrue(!blocked.stopConfirmed());
    }
}
