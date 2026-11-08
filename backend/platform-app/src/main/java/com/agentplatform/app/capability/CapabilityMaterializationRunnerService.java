package com.agentplatform.app.capability;

import com.agentplatform.api.capability.CapabilityMaterializationRunnerApi;
import com.agentplatform.api.chat.ChatApi;
import com.agentplatform.api.workspace.RunnerControlApi.SignedResult;
import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.capability.CapabilityBindings.Selection;
import com.agentplatform.domain.capability.CapabilityMaterialization;
import com.agentplatform.domain.capability.CapabilityMaterializationCommand;
import com.agentplatform.domain.capability.CapabilityMaterializationReceipt;
import com.agentplatform.domain.capability.CapabilityMaterializationRepository;
import com.agentplatform.domain.files.AssetStore;
import com.agentplatform.domain.workspace.WorkspaceData;
import com.agentplatform.domain.workspace.WorkspaceFailure;
import com.agentplatform.domain.workspace.WorkspaceRepository;
import com.agentplatform.infrastructure.capability.SkillArchiveValidator;
import com.agentplatform.infrastructure.capability.SkillMaterialExtractor;
import com.agentplatform.infrastructure.capability.SkillMaterializationVerifier;
import com.agentplatform.infrastructure.workspace.DockerSandboxRuntime;
import com.agentplatform.infrastructure.workspace.CapabilityMaterializationFailure;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** Signed runner route for exact, read-only capability materialization canaries. */
public final class CapabilityMaterializationRunnerService implements CapabilityMaterializationRunnerApi {
    public static final String PATH="/internal/capability-materializations/v1";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Set<PosixFilePermission> PRIVATE=PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> READABLE_DIRECTORY=PosixFilePermissions.fromString("rwxr-xr-x");
    private static final Set<PosixFilePermission> READABLE_FILE=PosixFilePermissions.fromString("rw-r--r--");
    private final CapabilityMaterializationRepository repository;
    private final RunnerSignatures signatures;
    private final WorkspaceRepository workspaces;
    private final CapabilityBindingService bindings;
    private final SkillVersionService versions;
    private final AssetStore assets;
    private final DockerSandboxRuntime runtime;
    private final Path materialBase;
    private final String policyHash;

    public CapabilityMaterializationRunnerService(CapabilityMaterializationRepository repository,RunnerSignatures signatures,
            WorkspaceRepository workspaces,CapabilityBindingService bindings,SkillVersionService versions,AssetStore assets,
            DockerSandboxRuntime runtime,Path workspaceRoot,String policyHash) {
        this.repository=Objects.requireNonNull(repository);this.signatures=Objects.requireNonNull(signatures);
        this.workspaces=Objects.requireNonNull(workspaces);this.bindings=Objects.requireNonNull(bindings);
        this.versions=Objects.requireNonNull(versions);this.assets=Objects.requireNonNull(assets);this.runtime=Objects.requireNonNull(runtime);
        this.materialBase=Objects.requireNonNull(workspaceRoot).toAbsolutePath().normalize().resolve(".platform-capability-materials");
        if(policyHash==null||!policyHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("CAPABILITY_POLICY_INVALID");
        this.policyHash=policyHash;
    }

    @Override public SignedResult materialize(String token,byte[] body) {
        signatures.authenticate(token);
        if(body==null||body.length>65536)throw new WorkspaceFailure(413,"CAPABILITY_MATERIALIZATION_BODY_TOO_LARGE");
        CapabilityMaterializationCommand command;
        try {command=JSON.readValue(body,CapabilityMaterializationCommand.class);}
        catch(RuntimeException invalid){throw new WorkspaceFailure(400,"INVALID_CAPABILITY_MATERIALIZATION_COMMAND");}
        validate(command);signatures.verify(token,binding(command,"POST",PATH),body);
        String requestHash=RunnerSignatures.sha256(body);
        var actor=new Actor(command.actorId(),command.spaceId());
        authorize(command,actor);
        var existing=repository.find(command.operationId());
        if(existing!=null) {
            if(!requestHash.equals(existing.requestHash()))throw new WorkspaceFailure(409,"CAPABILITY_MATERIALIZATION_COMMAND_CONFLICT");
            var receipt=storedOrPending(command,existing);
            if(receipt.stopConfirmed())cleanupAfterReceipt(command,receipt,materialRoot(command.operationId()));
            return signed(command,receipt,PATH);
        }
        String commandJson=JSON.writeValueAsString(command);
        if(!repository.accept(command,requestHash,commandJson)) {
            existing=repository.find(command.operationId());
            if(existing==null||!requestHash.equals(existing.requestHash()))throw new WorkspaceFailure(409,"CAPABILITY_MATERIALIZATION_COMMAND_CONFLICT");
            return signed(command,storedOrPending(command,existing),PATH);
        }
        Path operationRoot=null;boolean physicalStarted=false;
        try {
            authority(command,actor);
            createPrivateDirectory(materialBase);
            operationRoot=materialBase.resolve(command.operationId().toString()).normalize();
            if(!operationRoot.startsWith(materialBase)||Files.exists(operationRoot,LinkOption.NOFOLLOW_LINKS))
                throw new WorkspaceFailure(409,"CAPABILITY_MATERIALIZATION_ROOT_CONFLICT");
            Files.createDirectory(operationRoot,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PRIVATE));
            var material=loadMaterial(command,actor);
            var versionsById=new HashMap<UUID,CapabilityMaterialization.Version>();
            for(var version:material.manifest().versions())versionsById.put(version.versionId(),version);
            var roots=new HashMap<String,Path>();
            var archiveRoot=operationRoot.resolve(".incoming");createPrivateDirectory(archiveRoot);
            var staging=new SkillMaterialStaging(assets,new SkillMaterializationVerifier(new SkillArchiveValidator()),archiveRoot);
            var extractor=new SkillMaterialExtractor(new SkillMaterializationVerifier(new SkillArchiveValidator()));
            var extracted=new ArrayList<Path>();
            for(var selected:command.selections()) {
                authority(command,actor);
                var source=material.sources().get(selected.versionId());
                var version=versionsById.get(selected.versionId());
                if(source==null||version==null||!source.equals(versions.material(actor,selected.capabilityId(),selected.versionId())))
                    throw new WorkspaceFailure(409,"CAPABILITY_MATERIALIZATION_SOURCE_CHANGED");
                try(var archive=staging.stage(source.objectKey(),source.archiveBytes(),version,()->authority(command,actor))) {
                    Path root=extractor.extract(archive.archive(),version,operationRoot,()->authority(command,actor));
                    makeSandboxReadable(root);extracted.add(root);roots.put(version.mountTarget(),root);
                }
            }
            authority(command,actor);
            physicalStarted=true;
            CapabilityMaterializationReceipt receipt=runtime.verifyCapabilityMounts(command,material.manifest(),roots,policyHash,
                    ()->authority(command,actor));
            if(!"COMMITTED".equals(receipt.state()))throw new WorkspaceFailure(503,"CAPABILITY_MATERIALIZATION_RESULT_UNKNOWN");
            String receiptJson=JSON.writeValueAsString(receipt);
            repository.committed(command,receiptJson,RunnerSignatures.sha256(receiptJson.getBytes(StandardCharsets.UTF_8)));
            cleanupAfterReceipt(command,receipt,operationRoot);
            return signed(command,receipt,PATH);
        } catch(CapabilityMaterializationFailure physicalFailure) {
            var receipt=physicalFailure.receipt();
            String receiptJson=JSON.writeValueAsString(receipt);
            String receiptHash=RunnerSignatures.sha256(receiptJson.getBytes(StandardCharsets.UTF_8));
            if(receipt.stopConfirmed()) {
                repository.failed(command,receipt.errorCode(),receiptJson,receiptHash);
                cleanupAfterReceipt(command,receipt,operationRoot);
            } else {
                repository.blocked(command,receipt.errorCode(),receiptJson,receiptHash);
            }
            return signed(command,receipt,PATH);
        } catch(RuntimeException|IOException failure) {
            String code=stableCode(failure);
            try {repository.blocked(command,code,null,null);} catch(RuntimeException persistence) {failure.addSuppressed(persistence);}
            // If Docker was entered, keep the material until a signed stop receipt is durable.
            if(!physicalStarted&&operationRoot!=null)try {removePrivate(operationRoot);} catch(IOException cleanup) {failure.addSuppressed(cleanup);}
            var row=repository.find(command.operationId());
            return signed(command,row==null?pending(command):storedOrPending(command,row),PATH);
        }
    }

    @Override public SignedResult query(String token,String operationId) {
        String path=PATH+"/"+operationId;
        var request=signatures.verifiedQuery(token,path);
        UUID id;try{id=UUID.fromString(operationId);}catch(RuntimeException invalid){throw new WorkspaceFailure(400,"INVALID_CAPABILITY_MATERIALIZATION_COMMAND");}
        if(!request.operationId().equals(id))throw new WorkspaceFailure(401,"RUNNER_COMMAND_BINDING_REJECTED");
        var record=repository.find(id);if(record==null)throw new WorkspaceFailure(404,"CAPABILITY_MATERIALIZATION_NOT_FOUND");
        CapabilityMaterializationCommand command;
        try {command=JSON.readValue(record.commandJson(),CapabilityMaterializationCommand.class);}
        catch(RuntimeException invalid){throw new WorkspaceFailure(503,"CAPABILITY_MATERIALIZATION_RECORD_INVALID");}
        validate(command);
        signatures.verify(token,binding(command,"GET",path),new byte[0]);
        if(!request.commandId().equals(command.commandId())||!record.operationId().equals(command.operationId()))
            throw new WorkspaceFailure(401,"RUNNER_COMMAND_BINDING_REJECTED");
        return signed(command,storedOrPending(command,record),path);
    }

    private CapabilityMaterialization.Manifest authority(CapabilityMaterializationCommand command,Actor actor) {
        if(!repository.currentLease(command))throw new WorkspaceFailure(409,"CAPABILITY_MATERIALIZATION_STALE_OWNER");
        var workspace=workspaces.get(new WorkspaceData.Actor(actor.userId(),actor.spaceId()),command.workspaceId());
        if(!"READY".equals(workspace.state())||!workspace.id().equals(command.workspaceId())||!workspace.spaceId().equals(command.spaceId()))
            throw new WorkspaceFailure(409,"CAPABILITY_MATERIALIZATION_WORKSPACE_UNAVAILABLE");
        var effective=bindings.effective(actor,command.workspaceId());
        if(!effective.selections().containsAll(command.selections())
                ||command.selections().stream().anyMatch(effective.unavailable()::contains))
            throw new WorkspaceFailure(403,"CAPABILITY_BINDING_UNAVAILABLE");
        return loadMaterial(command,actor).manifest();
    }

    private void authorize(CapabilityMaterializationCommand command,Actor actor) {
        var manifest=authority(command,actor);
        if(!manifest.hash().equals(command.manifestHash()))throw new WorkspaceFailure(409,"CAPABILITY_MATERIALIZATION_MANIFEST_STALE");
    }

    private Material loadMaterial(CapabilityMaterializationCommand command,Actor actor) {
        var sources=new HashMap<UUID,com.agentplatform.domain.capability.SkillVersionRepository.MaterialSource>();
        var materialVersions=new ArrayList<CapabilityMaterialization.Version>();
        for(Selection selection:command.selections()) {
            var source=versions.material(actor,selection.capabilityId(),selection.versionId());
            var resources=JSON.readValue(source.manifestJson(),com.agentplatform.api.capability.SkillVersionApi.Resource[].class);
            var files=java.util.Arrays.stream(resources).map(file->new CapabilityMaterialization.File(file.path(),file.bytes(),file.sha256())).toList();
            sources.put(source.versionId(),source);
            materialVersions.add(new CapabilityMaterialization.Version(source.capabilityId(),source.versionId(),source.archiveSha256(),files));
        }
        return new Material(new CapabilityMaterialization.Manifest(command.spaceId(),command.taskId(),materialVersions),Map.copyOf(sources));
    }

    private record Material(CapabilityMaterialization.Manifest manifest,
            Map<UUID,com.agentplatform.domain.capability.SkillVersionRepository.MaterialSource> sources) {}

    private CapabilityMaterializationReceipt storedOrPending(CapabilityMaterializationCommand command,
            CapabilityMaterializationRepository.Record record) {
        if("COMMITTED".equals(record.state())) {
            if(record.receiptJson()==null||record.receiptHash()==null
                    ||!record.receiptHash().equals(RunnerSignatures.sha256(record.receiptJson().getBytes(StandardCharsets.UTF_8))))
                throw new WorkspaceFailure(503,"CAPABILITY_MATERIALIZATION_RECEIPT_CORRUPT");
            var receipt=JSON.readValue(record.receiptJson(),CapabilityMaterializationReceipt.class);
            if(!command.commandId().equals(receipt.commandId())||!command.manifestHash().equals(receipt.manifestHash()))
                throw new WorkspaceFailure(503,"CAPABILITY_MATERIALIZATION_RECEIPT_MISMATCH");
            return receipt;
        }
        String state=Set.of("BLOCKED","FAILED").contains(record.state())?record.state():"RUNNING";
        if(record.receiptJson()!=null) {
            if(record.receiptHash()==null||!record.receiptHash().equals(RunnerSignatures.sha256(record.receiptJson().getBytes(StandardCharsets.UTF_8))))
                throw new WorkspaceFailure(503,"CAPABILITY_MATERIALIZATION_RECEIPT_CORRUPT");
            var receipt=JSON.readValue(record.receiptJson(),CapabilityMaterializationReceipt.class);
            if(!command.commandId().equals(receipt.commandId())||!command.operationId().equals(receipt.operationId())
                    ||!command.workspaceId().equals(receipt.workspaceId())||command.generation()!=receipt.generation()
                    ||command.fence()!=receipt.fence()||!command.manifestHash().equals(receipt.manifestHash()))
                throw new WorkspaceFailure(503,"CAPABILITY_MATERIALIZATION_RECEIPT_MISMATCH");
            return receipt;
        }
        return new CapabilityMaterializationReceipt(command.commandId(),command.operationId(),command.workspaceId(),
                command.generation(),command.fence(),state,command.manifestHash(),null,0,false,null,null,null,
                null,null,"RUNNING".equals(state)?null:record.errorCode(),Math.max(1,record.revision()),false);
    }

    private CapabilityMaterializationReceipt pending(CapabilityMaterializationCommand command) {
        return new CapabilityMaterializationReceipt(command.commandId(),command.operationId(),command.workspaceId(),
                command.generation(),command.fence(),"RUNNING",command.manifestHash(),null,0,false,null,null,null,null,null,null,1,false);
    }

    private void cleanupAfterReceipt(CapabilityMaterializationCommand command,CapabilityMaterializationReceipt receipt,Path operationRoot) {
        runtime.removeCapabilityMaterialization(command,receipt);
        try {removePrivate(operationRoot);} catch(IOException cleanup) {throw new WorkspaceFailure(503,"CAPABILITY_MATERIALIZATION_CLEANUP_PENDING");}
    }

    private Path materialRoot(UUID operationId) {return materialBase.resolve(operationId.toString()).normalize();}

    private SignedResult signed(CapabilityMaterializationCommand command,CapabilityMaterializationReceipt receipt,String path) {
        byte[] body=JSON.writeValueAsBytes(receipt);
        var binding=binding(command,"RESPONSE",path);
        return new SignedResult(body,signatures.sign(binding,body));
    }

    private static RunnerSignatures.Binding binding(CapabilityMaterializationCommand command,String method,String path) {
        return new RunnerSignatures.Binding(method,path,command.commandId(),command.operationId(),command.spaceId(),
                command.workspaceId(),command.generation(),command.fence());
    }

    private static void validate(CapabilityMaterializationCommand command) {
        if(command==null||!command.operationId().equals(command.commandId())||command.selections().isEmpty())
            throw new WorkspaceFailure(400,"INVALID_CAPABILITY_MATERIALIZATION_COMMAND");
    }

    private static String stableCode(Throwable failure) {
        String code=failure instanceof WorkspaceFailure workspace?workspace.code():failure.getMessage();
        return code!=null&&code.matches("[A-Z0-9_]{1,64}")?code:"CAPABILITY_MATERIALIZATION_FAILED";
    }

    private static void createPrivateDirectory(Path path) throws IOException {
        Path fixed=path.toAbsolutePath().normalize();
        if(Files.exists(fixed,LinkOption.NOFOLLOW_LINKS)) {
            if(Files.isSymbolicLink(fixed)||!Files.isDirectory(fixed,LinkOption.NOFOLLOW_LINKS)||!fixed.toRealPath().equals(fixed))
                throw new IOException("CAPABILITY_MATERIALIZATION_PATH_INVALID");
        } else Files.createDirectories(fixed);
        try {Files.setPosixFilePermissions(fixed,PRIVATE);} catch(UnsupportedOperationException unsupported) {
            throw new IOException("CAPABILITY_MATERIALIZATION_POSIX_REQUIRED",unsupported);
        }
    }

    private static void makeSandboxReadable(Path root) throws IOException {
        try(var paths=Files.walk(root)) {
            for(Path path:paths.sorted(Comparator.comparingInt(Path::getNameCount)).toList()) {
                if(Files.isSymbolicLink(path))throw new IOException("CAPABILITY_MATERIALIZATION_LINK_REJECTED");
                Files.setPosixFilePermissions(path,Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)?READABLE_DIRECTORY:READABLE_FILE);
            }
        }
    }

    private static void removePrivate(Path root) throws IOException {
        if(root==null||!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return;
        Path fixed=root.toAbsolutePath().normalize();
        try(var paths=Files.walk(fixed)) {
            for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) {
                if(Files.isSymbolicLink(path))throw new IOException("CAPABILITY_MATERIALIZATION_LINK_REJECTED");
                Files.delete(path);
            }
        }
    }
}
