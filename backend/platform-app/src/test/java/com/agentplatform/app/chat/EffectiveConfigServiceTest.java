package com.agentplatform.app.chat;

import static org.junit.jupiter.api.Assertions.*;
import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.domain.chat.ConfigurationResolver;
import com.agentplatform.domain.chat.WorkspaceConfigurationRepository;
import com.agentplatform.types.TaskPermissionData.Mode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class EffectiveConfigServiceTest {
    private static final class Repository implements WorkspaceConfigurationRepository {
        boolean authorized=true, modelEnabled=true, initialized, audited;
        long version;
        Overrides values=new Overrides(null,null,null,null);
        @Override public boolean authorized(Actor a, UUID w) { return authorized; }
        @Override public void lockConfiguration(Actor a, UUID w) {}
        @Override public boolean modelEnabled(UUID model) { return modelEnabled; }
        @Override public View find(Actor a, UUID w) { return new View(version,values); }
        @Override public void initialize(Actor a, UUID w) { initialized=true; }
        @Override public boolean update(Actor a, UUID w, long expected, Patch p) {
            if(expected!=version) return false;
            values=new Overrides(p.modelPresent()?p.modelId():values.modelId(),
                    p.credentialPresent()?p.credentialSource():values.credentialSource(),
                    p.planPresent()?p.planMode():values.planMode(),p.permissionPresent()?p.permissionMode():values.permissionMode());
            version++; return true;
        }
        @Override public void audit(Actor a, UUID w, UUID trace) { audited=true; }
    }
    private static TransactionTemplate transactions() {
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) { return new SimpleTransactionStatus(); }
            @Override public void commit(TransactionStatus status) {}
            @Override public void rollback(TransactionStatus status) {}
        });
    }
    @Test void clearPreservesRevisionAndStaleVersionIsRejected() {
        var repo=new Repository(); var service=new EffectiveConfigService(repo,transactions(),null);
        var actor=new WorkspaceConfigurationRepository.Actor(UUID.randomUUID(),UUID.randomUUID()); var workspace=UUID.randomUUID();
        var set=new WorkspaceConfigurationRepository.Patch(false,null,false,null,true,false,true,Mode.FULL);
        var saved=service.update(actor,workspace,0,set,UUID.randomUUID());
        assertEquals(1,saved.version()); assertFalse(saved.overrides().planMode()); assertTrue(repo.audited);
        var clear=new WorkspaceConfigurationRepository.Patch(false,null,false,null,true,null,true,null);
        var cleared=service.update(actor,workspace,1,clear,UUID.randomUUID());
        assertEquals(2,cleared.version()); assertNull(cleared.overrides().planMode()); assertNull(cleared.overrides().permissionMode());
        assertEquals("WORKSPACE_CONFIGURATION_VERSION_CONFLICT",assertThrows(ChatFailure.class,()->service.update(actor,workspace,0,set,UUID.randomUUID())).code());
    }
    @Test void accessAndPlatformLocksAreCheckedBeforeMutation() {
        var repo=new Repository();
        var locked=new ConfigurationResolver.Layer(null,null,null,Mode.RISK_BASED);
        var service=new EffectiveConfigService(repo,transactions(),locked);
        var actor=new WorkspaceConfigurationRepository.Actor(UUID.randomUUID(),UUID.randomUUID()); var workspace=UUID.randomUUID();
        repo.authorized=false;
        assertEquals(404,assertThrows(ChatFailure.class,()->service.read(actor,workspace)).status());
        repo.authorized=true;
        var request=new WorkspaceConfigurationRepository.Patch(false,null,false,null,false,null,true,Mode.FULL);
        assertEquals(422,assertThrows(ChatFailure.class,()->service.update(actor,workspace,0,request,UUID.randomUUID())).status());
        assertFalse(repo.initialized);
    }
    @Test void personalOverridesCannotContradictDeploymentLocks() {
        var repository=org.mockito.Mockito.mock(com.agentplatform.domain.chat.ChatRepository.class);
        var service=new ChatService(repository,new com.agentplatform.infrastructure.chat.ChatCursor(new byte[32]));
        service.configureLocks(new ConfigurationResolver.Layer(null,null,false,Mode.RISK_BASED));
        var caller=new com.agentplatform.api.chat.ChatApi.Caller(UUID.randomUUID().toString(),UUID.randomUUID().toString());
        assertEquals(422,assertThrows(ChatFailure.class,()->service.preferences(caller,0,java.util.Map.of("defaultPlanEnabled",true))).status());
        org.mockito.Mockito.verifyNoInteractions(repository);
        var clear=new java.util.HashMap<String,Object>();clear.put("defaultPlanEnabled",null);
        service.preferences(caller,0,clear);
        org.mockito.Mockito.verify(repository).preferences(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(0L),org.mockito.ArgumentMatchers.eq(clear));
    }
}
