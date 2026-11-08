package com.agentplatform.app.capability;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.capability.CapabilityBindings;
import com.agentplatform.domain.capability.SkillVersionRepository.MaterialSource;
import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.domain.chat.ChatRepository;
import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.infrastructure.capability.SkillArchiveValidator;
import com.agentplatform.infrastructure.capability.SkillMaterializationVerifier;
import com.agentplatform.types.TaskPermissionData;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskSkillMaterialServiceTest {
    @TempDir Path temporary;

    @Test void taskScopeAndLiveBindingAreRequiredThroughoutStaging() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("SKILL.md"));
            zip.write("---\nname: material-test\ndescription: test\n---\nbody\n".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        byte[] archive = bytes.toByteArray(); Path seed = temporary.resolve("seed.zip"); Files.write(seed, archive);
        var validator = new SkillArchiveValidator(); var actual = validator.validate(seed);
        var user=UUID.randomUUID(); var space=UUID.randomUUID(); var workspace=UUID.randomUUID();
        var capability=UUID.randomUUID(); var version=UUID.randomUUID(); var task=UUID.randomUUID();
        var lease=new Lease(task,space,user,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),7,
                UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID());
        var actor=new Actor(user,space); var selected=new CapabilityBindings.Selection(capability,version);
        var source=new MaterialSource(capability,version,"spaces/private-fixture",archive.length,actual.archiveSha256(),
                tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(actual.files()));
        var chat=mock(ChatRepository.class); var bindings=mock(CapabilityBindingService.class); var versions=mock(SkillVersionService.class);
        var effective=new CapabilityBindingService.Effective(CapabilityBindings.Source.PERSONAL,List.of(selected),List.of());
        when(bindings.effective(actor,workspace)).thenReturn(effective);
        when(versions.material(actor,capability,version)).thenReturn(source);
        when(chat.permissions(any(),eq(task))).thenReturn(new TaskPermissionData.View(task.toString(),workspace.toString(),
                TaskPermissionData.Mode.RISK_BASED,1,"test",true,List.of(),List.of()));
        var store=new SkillValidationServiceTest.Store(archive);
        var staging=new SkillMaterialStaging(store,new SkillMaterializationVerifier(validator),temporary);
        var service=new TaskSkillMaterialService(chat,bindings,versions,staging);
        Path staged;
        try(var prepared=service.prepare(lease,workspace,List.of(selected))) {
            assertEquals(task,prepared.manifest().taskId()); assertEquals(space,prepared.manifest().spaceId());
            assertEquals(version,prepared.manifest().versions().getFirst().versionId());
            staged=prepared.archives().getFirst(); assertTrue(Files.exists(staged));
        }
        assertFalse(Files.exists(staged)); verify(chat,atLeast(3)).check(lease);

        when(chat.permissions(any(),eq(task))).thenReturn(new TaskPermissionData.View(task.toString(),UUID.randomUUID().toString(),
                TaskPermissionData.Mode.RISK_BASED,1,"test",true,List.of(),List.of()));
        assertEquals("SKILL_TASK_SCOPE_REJECTED",assertThrows(FileFailure.class,()->service.prepare(lease,workspace,List.of(selected))).getMessage());
        when(chat.permissions(any(),eq(task))).thenReturn(new TaskPermissionData.View(task.toString(),workspace.toString(),
                TaskPermissionData.Mode.RISK_BASED,1,"test",true,List.of(),List.of()));
        var calls=new AtomicInteger();
        when(bindings.effective(actor,workspace)).thenAnswer(ignored -> calls.incrementAndGet()==1 ? effective :
                new CapabilityBindingService.Effective(CapabilityBindings.Source.PERSONAL,List.of(selected),List.of(selected)));
        assertEquals("SKILL_BINDING_UNAVAILABLE",assertThrows(FileFailure.class,()->service.prepare(lease,workspace,List.of(selected))).getMessage());
        try(var files=Files.list(temporary)){assertEquals(1,files.count(),"failed staging must not leave an archive");}
    }
}
