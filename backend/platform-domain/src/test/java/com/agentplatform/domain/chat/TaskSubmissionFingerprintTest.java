package com.agentplatform.domain.chat;

import static org.junit.jupiter.api.Assertions.*;
import com.agentplatform.types.AttachmentReference;
import com.agentplatform.types.TaskPermissionData.Mode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskSubmissionFingerprintTest {
    @Test void requestIdentityDistinguishesOmissionAndExplicitDefaults() {
        var omitted=new TaskSubmissionFingerprint.Request(null,"hello",null,null,null,null,null,null,List.of());
        var explicit=new TaskSubmissionFingerprint.Request(null,"hello",null,null,null,false,Mode.RISK_BASED,null,List.of());
        assertNotEquals(TaskSubmissionFingerprint.compute(omitted),TaskSubmissionFingerprint.compute(explicit));
        assertEquals(TaskSubmissionFingerprint.compute(omitted),TaskSubmissionFingerprint.compute(omitted));
    }
    @Test void attachmentOrderAndPreconditionArePartOfTheRequest() {
        var a=new AttachmentReference("CHAT_ATTACHMENT",null,null,UUID.randomUUID().toString(),UUID.randomUUID().toString());
        var b=new AttachmentReference("CHAT_ATTACHMENT",null,null,UUID.randomUUID().toString(),UUID.randomUUID().toString());
        var first=new TaskSubmissionFingerprint.Request("title","文本",null,null,null,null,null,"a".repeat(64),List.of(a,b));
        var second=new TaskSubmissionFingerprint.Request("title","文本",null,null,null,null,null,"a".repeat(64),List.of(b,a));
        var third=new TaskSubmissionFingerprint.Request("title","文本",null,null,null,null,null,"b".repeat(64),List.of(a,b));
        assertNotEquals(TaskSubmissionFingerprint.compute(first),TaskSubmissionFingerprint.compute(second));
        assertNotEquals(TaskSubmissionFingerprint.compute(first),TaskSubmissionFingerprint.compute(third));
    }
}
