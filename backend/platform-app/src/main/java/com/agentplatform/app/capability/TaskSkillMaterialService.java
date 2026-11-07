package com.agentplatform.app.capability;

import com.agentplatform.api.capability.SkillVersionApi;
import com.agentplatform.domain.capability.CapabilityBindingRepository;
import com.agentplatform.domain.capability.CapabilityBindings.Selection;
import com.agentplatform.domain.capability.CapabilityMaterialization;
import com.agentplatform.domain.capability.SkillVersionRepository.MaterialSource;
import com.agentplatform.domain.chat.ChatData;
import com.agentplatform.domain.chat.ChatRepository;
import com.agentplatform.domain.files.FileFailure;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** Stages selected material under an owned task lease. Mounting requires a separate durable runner command. */
public final class TaskSkillMaterialService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ChatRepository chat;
    private final CapabilityBindingService bindings;
    private final SkillVersionService versions;
    private final SkillMaterialStaging staging;

    public TaskSkillMaterialService(ChatRepository chat, CapabilityBindingService bindings,
            SkillVersionService versions, SkillMaterialStaging staging) {
        this.chat=Objects.requireNonNull(chat); this.bindings=Objects.requireNonNull(bindings);
        this.versions=Objects.requireNonNull(versions); this.staging=Objects.requireNonNull(staging);
    }

    public Prepared prepare(ChatData.Lease lease, UUID workspace, List<Selection> selections) throws IOException {
        Objects.requireNonNull(lease); Objects.requireNonNull(workspace); Objects.requireNonNull(selections);
        var actor = new CapabilityBindingRepository.Actor(lease.userId(), lease.spaceId());
        var selected = List.copyOf(selections);
        guard(lease, workspace, actor, selected);
        var sources = new ArrayList<MaterialSource>(); var materialVersions = new ArrayList<CapabilityMaterialization.Version>();
        for (Selection selection : selected) {
            var source = versions.material(actor, selection.capabilityId(), selection.versionId());
            var resources = JSON.readValue(source.manifestJson(), SkillVersionApi.Resource[].class);
            var files = java.util.Arrays.stream(resources).map(file -> new CapabilityMaterialization.File(file.path(), file.bytes(), file.sha256())).toList();
            sources.add(source); materialVersions.add(new CapabilityMaterialization.Version(source.capabilityId(), source.versionId(), source.archiveSha256(), files));
        }
        sources.sort(java.util.Comparator.comparing((MaterialSource value) -> value.capabilityId().toString()).thenComparing(value -> value.versionId().toString()));
        materialVersions.sort(java.util.Comparator.comparing((CapabilityMaterialization.Version value) -> value.capabilityId().toString()).thenComparing(value -> value.versionId().toString()));
        var manifest = new CapabilityMaterialization.Manifest(lease.spaceId(), lease.taskId(), materialVersions);
        var staged = new ArrayList<SkillMaterialStaging.Staged>();
        try {
            for (int i=0;i<sources.size();i++) {
                MaterialSource expected=sources.get(i); var version=materialVersions.get(i);
                Runnable authorize=() -> {
                    guard(lease, workspace, actor, selected);
                    if (!expected.equals(versions.material(actor, expected.capabilityId(), expected.versionId())))
                        throw new FileFailure(409, "SKILL_MATERIAL_CHANGED");
                };
                staged.add(staging.stage(expected.objectKey(), expected.archiveBytes(), version, authorize));
            }
            guard(lease, workspace, actor, selected);
            for (var source : sources)
                if (!source.equals(versions.material(actor, source.capabilityId(), source.versionId())))
                    throw new FileFailure(409, "SKILL_MATERIAL_CHANGED");
            return new Prepared(manifest, staged);
        } catch (IOException | RuntimeException failure) {
            try { closeAll(staged); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private void guard(ChatData.Lease lease, UUID workspace, CapabilityBindingRepository.Actor actor, List<Selection> selected) {
        chat.check(lease);
        var permissions = chat.permissions(new ChatData.Actor(lease.userId(), lease.spaceId()), lease.taskId());
        if (permissions == null || !workspace.toString().equals(permissions.workspaceId())) throw new FileFailure(403, "SKILL_TASK_SCOPE_REJECTED");
        var effective = bindings.effective(actor, workspace);
        if (!effective.selections().containsAll(selected) || selected.stream().anyMatch(effective.unavailable()::contains))
            throw new FileFailure(403, "SKILL_BINDING_UNAVAILABLE");
    }

    private static void closeAll(List<SkillMaterialStaging.Staged> staged) throws IOException {
        IOException error=null;
        for (var item : staged) try { item.close(); } catch (IOException failure) {
            if(error==null)error=failure;else error.addSuppressed(failure);
        }
        if(error!=null)throw error;
    }

    public static final class Prepared implements AutoCloseable {
        private final CapabilityMaterialization.Manifest manifest;
        private final List<SkillMaterialStaging.Staged> staged;
        private Prepared(CapabilityMaterialization.Manifest manifest,List<SkillMaterialStaging.Staged> staged) {
            this.manifest=manifest;this.staged=List.copyOf(staged);
        }
        public CapabilityMaterialization.Manifest manifest(){return manifest;}
        public List<java.nio.file.Path> archives(){return staged.stream().map(SkillMaterialStaging.Staged::archive).toList();}
        @Override public void close() throws IOException {closeAll(staged);}
    }
}
