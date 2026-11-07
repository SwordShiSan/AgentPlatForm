package com.agentplatform.trigger.http;

import static org.junit.jupiter.api.Assertions.*;
import com.agentplatform.api.capability.SkillImportApi;
import com.agentplatform.api.chat.ChatApi;
import com.agentplatform.domain.files.FileFailure;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class SkillImportControllerTest {
    private static final class Fake implements SkillImportApi {
        private View view(String state) { return new View("id", "capability", "version", state, 3, "hash", 1); }
        @Override public View create(ChatApi.Caller c, String key, Create body) { return view("CREATED"); }
        @Override public View get(ChatApi.Caller c, String id) { return view("CREATED"); }
        @Override public View request(ChatApi.Caller c, String id) { return view("CREATED"); }
        @Override public View transfer(ChatApi.Caller c, String id, long length, InputStream stream) { assertEquals(3, length); return view("VERIFIED"); }
        @Override public View complete(ChatApi.Caller c, String id, String key) { return view("QUEUED"); }
    }
    @Test void createRequiresRequestIdentityAndContentUsesBoundedStream() throws Exception {
        var controller = new SkillImportController(new Fake());
        var request = new MockHttpServletRequest();
        RequestIdentity.attach(request, new ChatApi.Caller("user", "space"));
        var body = new SkillImportController.CreateBody(null, 3L, "a".repeat(64));
        assertEquals(428, assertThrows(FileFailure.class, () -> controller.create(request, body)).status());
        request.addHeader("Idempotency-Key", "request");
        assertEquals(201, controller.create(request, body).getStatusCode().value());
        request.setContent(new byte[]{1, 2, 3});
        assertEquals("VERIFIED", controller.transfer(request, "id").getBody().state());
        assertEquals(202, controller.complete(request, "id").getStatusCode().value());
    }
}
