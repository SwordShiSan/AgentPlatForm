package com.agentplatform.app.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentplatform.api.workspace.RunnerControlApi.SignedResult;
import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.capability.CapabilityBindings;
import com.agentplatform.domain.capability.CapabilityMaterialization;
import com.agentplatform.domain.capability.CapabilityMaterializationCommand;
import com.agentplatform.domain.capability.CapabilityMaterializationReceipt;
import com.agentplatform.domain.capability.CapabilityMaterializationRepository;
import com.agentplatform.domain.capability.SkillVersionRepository;
import com.agentplatform.domain.files.AssetStore;
import com.agentplatform.domain.workspace.WorkspaceData;
import com.agentplatform.domain.workspace.WorkspaceRepository;
import com.agentplatform.infrastructure.workspace.DockerSandboxRuntime;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CapabilityMaterializationRunnerServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T00:00:00Z"), ZoneOffset.UTC);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static RSAKey controlKey;
    private static RSAKey runnerKey;

    @BeforeAll
    static void keys() throws Exception {
        controlKey = new RSAKeyGenerator(3072).keyID("control-r62-test").generate();
        runnerKey = new RSAKeyGenerator(3072).keyID("runner-r62-test").generate();
    }

    @Test
    void replayOfCommittedSignedCommandReturnsReceiptWithoutStartingAnotherContainer() throws Exception {
        UUID space = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        UUID task = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        UUID capability = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        var selection = new CapabilityBindings.Selection(capability, versionId);
        byte[] skillBytes = "---\nname: test-skill\ndescription: R6.2 runner replay test\n---\n".getBytes(StandardCharsets.UTF_8);
        String skillHash = RunnerSignatures.sha256(skillBytes);
        String archiveHash = "a".repeat(64);
        var version = new CapabilityMaterialization.Version(capability, versionId, archiveHash,
                List.of(new CapabilityMaterialization.File("SKILL.md", skillBytes.length, skillHash)));
        var manifest = new CapabilityMaterialization.Manifest(space, task, List.of(version));
        var command = new CapabilityMaterializationCommand(operation, operation, space, task, attempt, user,
                UUID.randomUUID(), workspace, 3, 7, manifest.hash(), List.of(selection));
        byte[] request = JSON.writeValueAsBytes(command);

        var repo = mock(CapabilityMaterializationRepository.class);
        var workspaces = mock(WorkspaceRepository.class);
        var bindings = mock(CapabilityBindingService.class);
        var versions = mock(SkillVersionService.class);
        var assets = mock(AssetStore.class);
        var runtime = mock(DockerSandboxRuntime.class);
        var source = new SkillVersionRepository.MaterialSource(capability, versionId, "private-test-object",
                1, archiveHash, JSON.writeValueAsString(List.of(new com.agentplatform.api.capability.SkillVersionApi.Resource(
                        "SKILL.md", skillBytes.length, skillHash))));
        when(repo.currentLease(command)).thenReturn(true);
        when(workspaces.get(new WorkspaceData.Actor(user, space), workspace)).thenReturn(
                new WorkspaceData.Workspace(workspace, space, user, "test", "READY", "NOT_CREATED",
                        WorkspaceData.QUOTA_BYTES, WorkspaceData.QUOTA_INODES, 1, null, null, Instant.EPOCH));
        when(bindings.effective(new Actor(user, space), workspace)).thenReturn(
                new CapabilityBindingService.Effective(CapabilityBindings.Source.PERSONAL, List.of(selection), List.of()));
        when(versions.material(new Actor(user, space), capability, versionId)).thenReturn(source);
        var storedReceipt = new CapabilityMaterializationReceipt(operation, operation, workspace, 3, 7,
                "COMMITTED", manifest.hash(), "b".repeat(64), 1, true, 0, "container-id", "sha256:image",
                Instant.EPOCH, Instant.EPOCH.plusSeconds(1), null, 2, true);
        byte[] receiptBytes = JSON.writeValueAsBytes(storedReceipt);
        when(repo.find(operation)).thenReturn(new CapabilityMaterializationRepository.Record(operation,
                JSON.writeValueAsString(command), RunnerSignatures.sha256(request), "COMMITTED",
                new String(receiptBytes, StandardCharsets.UTF_8), RunnerSignatures.sha256(receiptBytes), null, 2));

        var control = new RunnerSignatures(controlKey, runnerKey.toPublicJWK(), "control", "runner", CLOCK);
        var runnerSignatures = new RunnerSignatures(runnerKey, controlKey.toPublicJWK(), "runner", "control", CLOCK);
        var service = new CapabilityMaterializationRunnerService(repo, runnerSignatures, workspaces, bindings,
                versions, assets, runtime, java.nio.file.Path.of("target", "r62-runner-service-test"), "b".repeat(64));
        String token = control.sign(new RunnerSignatures.Binding("POST", CapabilityMaterializationRunnerService.PATH,
                operation, operation, space, workspace, 3, 7), request);

        SignedResult result = service.materialize(token, request);
        assertNotNull(result);
        var responseBinding = new RunnerSignatures.Binding("RESPONSE", CapabilityMaterializationRunnerService.PATH,
                operation, operation, space, workspace, 3, 7);
        control.verify(result.signature(), responseBinding, result.body());
        assertEquals("COMMITTED", JSON.readValue(result.body(), CapabilityMaterializationReceipt.class).state());
        verify(repo, never()).accept(any(), any(), any());
        verify(runtime, never()).verifyCapabilityMounts(any(), any(), any(), any(), any());
        verify(runtime).removeCapabilityMaterialization(eq(command), eq(storedReceipt));
        verify(assets, never()).open(any(), any(), any());
    }
}
