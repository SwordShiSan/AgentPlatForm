package com.agentplatform.app.capability;

import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.capability.CapabilityBindingRepository;
import com.agentplatform.domain.capability.CapabilityBindings.Selection;
import com.agentplatform.domain.capability.CapabilityMaterialization;
import com.agentplatform.domain.capability.CapabilityMaterializationCommand;
import com.agentplatform.domain.capability.CapabilityMaterializationReceipt;
import com.agentplatform.domain.capability.CapabilityMaterializationRunnerPort;
import com.agentplatform.domain.chat.ChatData;
import com.agentplatform.domain.chat.ChatRepository;
import com.agentplatform.domain.chat.ChatFailure;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** Worker-only, protected test path. Capability material is not added to the production tool catalog in R6.2. */
public final class CapabilityMaterializationControlService {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final ChatRepository chat;
    private final CapabilityBindingService bindings;
    private final SkillVersionService versions;
    private final CapabilityMaterializationRunnerPort runner;
    public CapabilityMaterializationControlService(ChatRepository chat,CapabilityBindingService bindings,
            SkillVersionService versions,CapabilityMaterializationRunnerPort runner) {
        this.chat=Objects.requireNonNull(chat);this.bindings=Objects.requireNonNull(bindings);
        this.versions=Objects.requireNonNull(versions);this.runner=Objects.requireNonNull(runner);
    }

    public CapabilityMaterializationReceipt materialize(ChatData.Lease lease,UUID workspaceId,List<Selection> selected) {
        Objects.requireNonNull(lease);Objects.requireNonNull(workspaceId);Objects.requireNonNull(selected);
        var actor=new Actor(lease.userId(),lease.spaceId());var fixed=List.copyOf(selected);
        authorize(lease,workspaceId,actor,fixed);
        var materialVersions=new ArrayList<CapabilityMaterialization.Version>();
        for(var selection:fixed) {
            var source=versions.material(actor,selection.capabilityId(),selection.versionId());
            var resources=JSON.readValue(source.manifestJson(),com.agentplatform.api.capability.SkillVersionApi.Resource[].class);
            var files=java.util.Arrays.stream(resources).map(file->new CapabilityMaterialization.File(file.path(),file.bytes(),file.sha256())).toList();
            materialVersions.add(new CapabilityMaterialization.Version(source.capabilityId(),source.versionId(),source.archiveSha256(),files));
        }
        var manifest=new CapabilityMaterialization.Manifest(lease.spaceId(),lease.taskId(),materialVersions);
        authorize(lease,workspaceId,actor,fixed);
        UUID operation=UUID.nameUUIDFromBytes((lease.taskId()+"|"+lease.attemptId()+"|"+manifest.hash()).getBytes(StandardCharsets.UTF_8));
        var command=new CapabilityMaterializationCommand(operation,operation,lease.spaceId(),lease.taskId(),lease.attemptId(),
                lease.userId(),lease.ownerId(),workspaceId,lease.generation(),lease.fence(),manifest.hash(),fixed);
        var receipt=runner.materialize(command);
        authorize(lease,workspaceId,actor,fixed);
        if(!"COMMITTED".equals(receipt.state()))throw new ChatFailure(503,"CAPABILITY_MATERIALIZATION_"+receipt.state());
        return receipt;
    }

    public CapabilityMaterializationReceipt query(ChatData.Lease lease,UUID workspaceId,List<Selection> selected) {
        Objects.requireNonNull(lease);Objects.requireNonNull(workspaceId);Objects.requireNonNull(selected);
        var actor=new Actor(lease.userId(),lease.spaceId());var fixed=List.copyOf(selected);
        authorize(lease,workspaceId,actor,fixed);
        var versionsForManifest=new ArrayList<CapabilityMaterialization.Version>();
        for(var selection:fixed) {
            var source=versions.material(actor,selection.capabilityId(),selection.versionId());
            var resources=JSON.readValue(source.manifestJson(),com.agentplatform.api.capability.SkillVersionApi.Resource[].class);
            versionsForManifest.add(new CapabilityMaterialization.Version(source.capabilityId(),source.versionId(),source.archiveSha256(),
                    java.util.Arrays.stream(resources).map(file->new CapabilityMaterialization.File(file.path(),file.bytes(),file.sha256())).toList()));
        }
        var manifest=new CapabilityMaterialization.Manifest(lease.spaceId(),lease.taskId(),versionsForManifest);
        UUID operation=UUID.nameUUIDFromBytes((lease.taskId()+"|"+lease.attemptId()+"|"+manifest.hash()).getBytes(StandardCharsets.UTF_8));
        var command=new CapabilityMaterializationCommand(operation,operation,lease.spaceId(),lease.taskId(),lease.attemptId(),
                lease.userId(),lease.ownerId(),workspaceId,lease.generation(),lease.fence(),manifest.hash(),fixed);
        return runner.query(command);
    }

    private void authorize(ChatData.Lease lease,UUID workspace,Actor actor,List<Selection> selected) {
        chat.check(lease);
        var permission=chat.permissions(new ChatData.Actor(lease.userId(),lease.spaceId()),lease.taskId());
        if(permission==null||!workspace.toString().equals(permission.workspaceId()))throw new ChatFailure(403,"SKILL_TASK_SCOPE_REJECTED");
        var effective=bindings.effective(actor,workspace);
        if(!effective.selections().containsAll(selected)||selected.stream().anyMatch(effective.unavailable()::contains))
            throw new ChatFailure(403,"SKILL_BINDING_UNAVAILABLE");
    }
}
