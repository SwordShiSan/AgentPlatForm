package com.agentplatform.domain.chat;

import static org.junit.jupiter.api.Assertions.*;
import com.agentplatform.types.TaskPermissionData.Mode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConfigurationResolverTest {
    @Test void requestFalseAndWorkspaceValuesOverridePersonalDefaults() {
        UUID platformModel = UUID.randomUUID(), personalModel = UUID.randomUUID();
        var platform = new ConfigurationResolver.Layer(platformModel, ConfigurationResolver.CredentialSource.PLATFORM, false, Mode.RISK_BASED);
        var personal = new ConfigurationResolver.Layer(personalModel, null, true, null);
        var workspace = new ConfigurationResolver.Layer(null, ConfigurationResolver.CredentialSource.USER, null, Mode.FULL);
        var request = new ConfigurationResolver.Layer(null, null, false, null);
        var result = ConfigurationResolver.resolve(platform, personal, workspace, request);
        assertEquals(personalModel, result.modelId().value());
        assertEquals(ConfigurationResolver.Source.USER, result.modelId().source());
        assertEquals(ConfigurationResolver.CredentialSource.USER, result.credentialSource().value());
        assertEquals(ConfigurationResolver.Source.WORKSPACE, result.credentialSource().source());
        assertFalse(result.planMode().value());
        assertEquals(ConfigurationResolver.Source.REQUEST, result.planMode().source());
        assertEquals(Mode.FULL, result.permissionMode().value());
    }

    @Test void MissingOverridesInheritPlatformAndRequireCompletePlatformDefaults() {
        var model = UUID.randomUUID();
        var platform = new ConfigurationResolver.Layer(model, ConfigurationResolver.CredentialSource.PLATFORM, false, Mode.RISK_BASED);
        var result = ConfigurationResolver.resolve(platform, null, null, null);
        assertEquals(ConfigurationResolver.Source.PLATFORM, result.modelId().source());
        assertFalse(result.planMode().value());
        assertThrows(NullPointerException.class, () -> ConfigurationResolver.resolve(new ConfigurationResolver.Layer(null, null, null, null), null, null, null));
    }

    @Test void platformLockIsExposedAndConflictingExplicitRequestIsRejected() {
        var model = UUID.randomUUID();
        var platform = new ConfigurationResolver.Layer(model, ConfigurationResolver.CredentialSource.PLATFORM, false, Mode.RISK_BASED);
        var workspace = new ConfigurationResolver.Layer(null, null, null, Mode.FULL);
        var locked = new ConfigurationResolver.Layer(null, null, null, Mode.RISK_BASED);
        var result = ConfigurationResolver.resolve(platform, null, workspace, null, locked);
        assertEquals(Mode.RISK_BASED, result.permissionMode().value());
        assertTrue(result.lockedFields().contains(ConfigurationResolver.Field.PERMISSION_MODE));
        var request = new ConfigurationResolver.Layer(null, null, null, Mode.FULL);
        var failure = assertThrows(ChatFailure.class, () -> ConfigurationResolver.resolve(platform, null, workspace, request, locked));
        assertEquals(422, failure.status());
        assertEquals("CONFIG_CONSTRAINT_VIOLATION", failure.code());
    }

    @Test void effectiveHashBindsSourceWorkspaceAndCredentialRevision() {
        var model=UUID.randomUUID();var credential=UUID.randomUUID();
        var platform=new ConfigurationResolver.Layer(model,ConfigurationResolver.CredentialSource.PLATFORM,false,Mode.RISK_BASED);
        var inherited=ConfigurationResolver.resolve(platform,null,null,null);
        var explicit=ConfigurationResolver.resolve(platform,null,null,new ConfigurationResolver.Layer(null,null,false,null));
        var snapshot=new ResolvedTaskConfiguration(null,inherited,1,credential,1,"price-1",2,8,"policy-1");
        assertEquals(snapshot.effectiveHash(),new ResolvedTaskConfiguration(null,inherited,1,credential,1,"price-1",2,8,"policy-1").effectiveHash());
        assertNotEquals(snapshot.effectiveHash(),new ResolvedTaskConfiguration(null,explicit,1,credential,1,"price-1",2,8,"policy-1").effectiveHash());
        assertNotEquals(snapshot.effectiveHash(),new ResolvedTaskConfiguration(UUID.randomUUID(),inherited,1,credential,1,"price-1",2,8,"policy-1").effectiveHash());
        assertNotEquals(snapshot.effectiveHash(),new ResolvedTaskConfiguration(null,inherited,1,credential,2,"price-1",2,8,"policy-1").effectiveHash());
        assertNotEquals(snapshot.effectiveHash(),new ResolvedTaskConfiguration(null,inherited,1,credential,1,"price-2",2,8,"policy-1").effectiveHash());
        assertThrows(IllegalArgumentException.class,()->new ResolvedTaskConfiguration(null,inherited,1,null,1,"",0,0,"policy-1"));
    }
}
