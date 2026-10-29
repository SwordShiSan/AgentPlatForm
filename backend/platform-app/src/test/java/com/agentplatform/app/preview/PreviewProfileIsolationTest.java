package com.agentplatform.app.preview;

import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import static org.junit.jupiter.api.Assertions.*;

/** Static member configurations are independently scanned; outer conditions do not protect them. */
class PreviewProfileIsolationTest {
    private Set<String> scanned(String profile,Map<String,String> properties) {
        var environment=new StandardEnvironment();environment.setActiveProfiles(profile);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("preview-profile-fixture",new HashMap<>(properties)));
        var scanner=new ClassPathScanningCandidateComponentProvider(false,environment);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Configuration.class));
        return scanner.findCandidateComponents("com.agentplatform.app.preview").stream()
                .map(definition->definition.getBeanClassName()).collect(Collectors.toSet());
    }
    @Test void deliveryCannotLeakWhenPreviewIsMissingOrDisabledInEitherWebOrWorkerProfile() {
        for(String profile:List.of("workspace-api","workspace-worker")) {
            var missing=Map.of("platform.workspace.enabled","true","platform.workspace.files.enabled","true");
            assertFalse(scanned(profile,missing).contains(PreviewConfiguration.Delivery.class.getName()));
            var disabled=new HashMap<>(missing);disabled.put("platform.preview.enabled","false");
            assertFalse(scanned(profile,disabled).contains(PreviewConfiguration.Delivery.class.getName()));
        }
    }
    @Test void deliveryRequiresAllThreeFeatureFlagsEvenWhenPreviewIsExplicitlyEnabled() {
        for(String profile:List.of("workspace-api","workspace-worker")) {
            var enabled=new HashMap<>(Map.of("platform.workspace.enabled","true","platform.workspace.files.enabled","true","platform.preview.enabled","true"));
            assertTrue(scanned(profile,enabled).contains(PreviewConfiguration.Delivery.class.getName()));
            enabled.put("platform.workspace.enabled","false");assertFalse(scanned(profile,enabled).contains(PreviewConfiguration.Delivery.class.getName()));
            enabled.put("platform.workspace.enabled","true");enabled.put("platform.workspace.files.enabled","false");
            assertFalse(scanned(profile,enabled).contains(PreviewConfiguration.Delivery.class.getName()));
        }
    }
}
