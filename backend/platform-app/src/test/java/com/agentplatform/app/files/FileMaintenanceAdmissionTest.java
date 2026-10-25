package com.agentplatform.app.files;

import com.agentplatform.domain.files.AssetStore;
import com.agentplatform.domain.files.FileRepository;
import com.agentplatform.domain.workspace.WorkspaceEffectBoundary;
import com.agentplatform.infrastructure.workspace.SpringFileMessaging;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FileMaintenanceAdmissionTest {
    @Configuration(proxyBeanMethods=false)
    @EnableConfigurationProperties(FileMaintenanceProperties.class)
    static class Binding {}

    @Test void absentSettingBindsEnabledAndMalformedSettingRejects() {
        var context=new ApplicationContextRunner().withUserConfiguration(Binding.class);
        context.run(value->assertTrue(value.getBean(FileMaintenanceProperties.class).scanAdmissionEnabled()));
        context.withPropertyValues("platform.workspace.files.maintenance.scan-admission-enabled=not-a-boolean")
                .run(value->assertNotNull(value.getStartupFailure()));
    }

    @Test void normalRelayStillAdmitsScans() { relay(true,false); }

    @Test void maintenanceStillPublishesAndReleasesTransfersWithoutAdmittingNewScans() { relay(false,true); }

    @Test void disabledAdmissionOutsideMaintenanceRejectsBeforeScheduling() {
        var environment=new StandardEnvironment();environment.setActiveProfiles("workspace-api");
        var timers=mock(ThreadPoolTaskScheduler.class);
        var failure=assertThrows(IllegalArgumentException.class,()->new FileMessagingConfiguration().fileLifecycle(
                mock(FileRepository.class),mock(SpringFileMessaging.class),timers,provider(),provider(),provider(),
                environment,WorkspaceEffectBoundary.NONE,mock(AssetStore.class),provider(),new FileMaintenanceProperties(false)));
        assertEquals("WORKSPACE_MAINTENANCE_PROFILE_REQUIRED",failure.getMessage());verifyNoInteractions(timers);
    }

    private static <T> ObjectProvider<T> provider() {
        @SuppressWarnings("unchecked") var value=(ObjectProvider<T>)mock(ObjectProvider.class);return value;
    }

    private void relay(boolean enabled,boolean maintenance) {
        var repository=mock(FileRepository.class);when(repository.publications(any())).thenReturn(List.of());
        var messaging=mock(SpringFileMessaging.class);var assets=mock(AssetStore.class);
        var timers=mock(ThreadPoolTaskScheduler.class);var future=mock(ScheduledFuture.class);
        doReturn(future).when(timers).scheduleWithFixedDelay(any(Runnable.class),any(Duration.class));
        var environment=new StandardEnvironment();environment.setActiveProfiles(maintenance
                ?new String[]{"workspace-api","workspace-maintenance"}:new String[]{"workspace-api"});
        var lifecycle=new FileMessagingConfiguration().fileLifecycle(repository,messaging,timers,provider(),provider(),provider(),
                environment,WorkspaceEffectBoundary.NONE,assets,provider(),new FileMaintenanceProperties(enabled));
        lifecycle.start();var scheduled=ArgumentCaptor.forClass(Runnable.class);
        verify(timers,times(2)).scheduleWithFixedDelay(scheduled.capture(),any(Duration.class));
        scheduled.getAllValues().forEach(Runnable::run);
        verify(assets).flushStoppedTransfers();verify(repository).blockExpired();verify(repository).publications(any());
        if(enabled)verify(repository).scheduleScan();else verify(repository,never()).scheduleScan();
        lifecycle.stop();verify(messaging).close();verify(future,times(2)).cancel(false);
    }
}
