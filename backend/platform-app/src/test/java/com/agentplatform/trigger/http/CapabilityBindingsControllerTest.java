package com.agentplatform.trigger.http;

import static org.junit.jupiter.api.Assertions.*;
import com.agentplatform.api.capability.CapabilityBindingsApi;
import com.agentplatform.api.chat.ChatApi;
import com.agentplatform.domain.chat.ChatFailure;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class CapabilityBindingsControllerTest {
    private static final class Fake implements CapabilityBindingsApi {
        @Override public Bindings read(ChatApi.Caller caller, String workspace) { return new Bindings(0, workspace != null, List.of()); }
        @Override public Bindings replace(ChatApi.Caller caller, String workspace, long expected, Update update, String key) {
            assertEquals(0, expected); assertFalse(update.inheritPersonal()); assertEquals("request-id", key);
            return new Bindings(1, false, update.selections());
        }
        @Override public Effective effective(ChatApi.Caller caller, String workspace) { return new Effective("PERSONAL", List.of(), List.of()); }
    }

    @Test void writeRequiresVersionAndRequestIdentityAndReturnsNewEtag() {
        var controller = new CapabilityBindingsController(new Fake());
        var request = new MockHttpServletRequest();
        RequestIdentity.attach(request, new ChatApi.Caller("user", "space"));
        var body = new CapabilityBindingsController.Body(false, List.of());
        assertEquals(428, assertThrows(ChatFailure.class, () -> controller.replacePersonal(request, body)).status());
        request.addHeader("If-Match", "\"0\"");
        assertEquals(428, assertThrows(ChatFailure.class, () -> controller.replacePersonal(request, body)).status());
        request.addHeader("Idempotency-Key", "request-id");
        var response = controller.replacePersonal(request, body);
        assertEquals("\"1\"", response.getHeaders().getETag());
        assertEquals("no-store", response.getHeaders().getFirst("Cache-Control"));
    }
}
