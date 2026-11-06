package com.agentplatform.trigger.http;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.validation.Validation;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class TypedHttpRequestsTest {
    private final JsonMapper mapper;
    TypedHttpRequestsTest() {
        var builder = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        HttpJsonConfiguration.configure(builder);
        mapper = builder.build();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"input\":{\"text\":7},\"options\":{}}",
            "{\"input\":{\"text\":\"a\"},\"options\":{\"planMode\":\"false\"}}",
            "{\"input\":{\"text\":\"a\"},\"options\":{\"modelId\":7}}",
            "{\"input\":{\"text\":\"a\",\"hidden\":1},\"options\":{}}",
            "{\"input\":{\"text\":\"a\"},\"options\":{\"hidden\":1}}"})
    void invalidScalarTypesAndUnknownNestedFieldsAreRejected(String json) {
        assertThrows(RuntimeException.class, () -> mapper.readValue(json, ChatRequests.Task.class));
    }

    @Test void beanValidationRejectsMissingNestedTaskFields() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertFalse(factory.getValidator().validate(mapper.readValue("{}", ChatRequests.Task.class)).isEmpty());
            assertFalse(factory.getValidator().validate(mapper.readValue("{\"input\":{},\"options\":{}}", ChatRequests.Task.class)).isEmpty());
        }
    }

    @Test void omittedInitialTaskIsCompatibleButExplicitNullIsRejected() {
        assertNull(mapper.readValue("{}", ChatRequests.Conversation.class).initialTask());
        assertThrows(RuntimeException.class, () -> mapper.readValue("{\"initialTask\":null}", ChatRequests.Conversation.class));
    }

    @Test void preferencePatchPreservesAbsenceVersusExplicitNull() {
        assertTrue(mapper.readValue("{}", ChatRequests.PreferencePatch.class).values().isEmpty());
        var patch = mapper.readValue("{\"defaultModelId\":null}", ChatRequests.PreferencePatch.class).values();
        assertTrue(patch.containsKey("defaultModelId"));
        assertNull(patch.get("defaultModelId"));
        assertEquals(Map.of("defaultCredentialSource", "USER"), mapper.readValue("{\"defaultCredentialSource\":\"USER\"}", ChatRequests.PreferencePatch.class).values());
        assertEquals(Map.of("defaultPlanEnabled", false, "defaultApprovalMode", "RISK_BASED"),
                mapper.readValue("{\"defaultPlanEnabled\":false,\"defaultApprovalMode\":\"RISK_BASED\"}", ChatRequests.PreferencePatch.class).values());
        var clear = mapper.readValue("{\"defaultPlanEnabled\":null,\"defaultApprovalMode\":null}", ChatRequests.PreferencePatch.class).values();
        assertTrue(clear.containsKey("defaultPlanEnabled"));
        assertTrue(clear.containsKey("defaultApprovalMode"));
        assertNull(clear.get("defaultPlanEnabled"));
        assertNull(clear.get("defaultApprovalMode"));
    }

    @Test void secretsAreNotExposedByRequestObjectRendering() {
        assertFalse(new ChatRequests.Credential("sentinel-secret").toString().contains("sentinel-secret"));
        assertFalse(new ChatRequests.Login("alice", "sentinel-password").toString().contains("sentinel-password"));
    }

    @Test void workspaceSettingsPatchRetainsFieldPresence() {
        var empty = mapper.readValue("{}", WorkspaceSettingsController.PatchBody.class).patch();
        assertFalse(empty.planPresent());
        var changed = mapper.readValue("{\"defaultPlanEnabled\":false,\"defaultApprovalMode\":null}",
                WorkspaceSettingsController.PatchBody.class).patch();
        assertTrue(changed.planPresent());
        assertFalse(changed.planMode());
        assertTrue(changed.permissionPresent());
        assertNull(changed.permissionMode());
        assertThrows(RuntimeException.class, () -> mapper.readValue("{\"unknown\":1}", WorkspaceSettingsController.PatchBody.class));
    }
}
