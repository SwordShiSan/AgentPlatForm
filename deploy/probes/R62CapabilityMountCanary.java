package com.agentplatform.app.workspace;

import com.agentplatform.domain.capability.CapabilityBindings;
import com.agentplatform.domain.capability.CapabilityMaterialization;
import com.agentplatform.domain.capability.CapabilityMaterializationCommand;
import com.agentplatform.infrastructure.workspace.CapabilityMaterializationFailure;
import com.agentplatform.domain.workspace.SandboxRuntime;
import com.agentplatform.domain.workspace.WorkspaceEffectBoundary;
import com.agentplatform.infrastructure.workspace.AuxiliaryContainerSlot;
import com.agentplatform.infrastructure.workspace.DockerSandboxRuntime;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import com.agentplatform.infrastructure.workspace.SandboxPolicy;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Short-lived Linux acceptance entrypoint. It exercises the production Java docker-java adapter,
 * while the caller mounts a previously provisioned isolated XFS image at workspaceRoot.
 */
public final class R62CapabilityMountCanary {
    private static final String CONTENT = "r62-java-material-read-only\n";
    private static final String CAPABILITY_POLICY = "b".repeat(64);

    private R62CapabilityMountCanary() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--self-check".equals(args[0])) {
            new CapabilityBindings.Selection(UUID.randomUUID(), UUID.randomUUID());
            Class.forName("com.agentplatform.infrastructure.workspace.DockerSandboxRuntime");
            Class.forName("com.agentplatform.infrastructure.workspace.SandboxPolicy");
            System.out.println("R62_JAVA_CANARY_READY");
            return;
        }
        if (args.length != 7) throw new IllegalArgumentException("R62_CANARY_ARGUMENTS_INVALID");
        Path workspaceRoot = Path.of(args[0]).toAbsolutePath().normalize();
        String sandboxImage = args[1];
        String helperImage = args[2];
        UUID mountUuid = UUID.fromString(args[3]);
        String quotaDevice = args[4];
        Path runtimeAttestation = Path.of(args[5]).toAbsolutePath().normalize();
        UUID operationId = UUID.fromString(args[6]);

        UUID spaceId = named(operationId, "space");
        UUID workspaceId = named(operationId, "workspace");
        UUID taskId = named(operationId, "task");
        UUID attemptId = named(operationId, "attempt");
        UUID actorId = named(operationId, "actor");
        UUID ownerId = named(operationId, "owner");
        UUID capabilityId = named(operationId, "capability");
        UUID versionId = named(operationId, "version");
        var selection = new CapabilityBindings.Selection(capabilityId, versionId);

        Path work = workspaceRoot.resolve(spaceId.toString()).resolve(workspaceId.toString()).resolve("work");
        Path material = workspaceRoot.resolve(".platform-capability-materials").resolve(operationId.toString())
                .resolve(capabilityId.toString()).resolve(versionId.toString());
        Files.createDirectories(work);
        Files.createDirectories(material);
        if (((Number) Files.getAttribute(work, "unix:uid")).intValue() != 10001
                || ((Number) Files.getAttribute(work, "unix:gid")).intValue() != 10001
                || !Files.getPosixFilePermissions(work).equals(PosixFilePermissions.fromString("rwx------"))) {
            throw new IllegalStateException("R62_CANARY_WORKSPACE_FIXTURE_OWNERSHIP_INVALID");
        }
        Path skill = material.resolve("SKILL.md");
        Files.writeString(skill, CONTENT);
        Files.setPosixFilePermissions(material.getParent().getParent().getParent(), PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(material.getParent().getParent(), PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(material.getParent(), PosixFilePermissions.fromString("rwxr-xr-x"));
        // Make the guest identity able to write the fixture if the bind accidentally becomes rw;
        // the attempted create must then fail because the mount itself is read-only.
        Files.setPosixFilePermissions(material, PosixFilePermissions.fromString("rwxrwxrwx"));
        Files.setPosixFilePermissions(skill, PosixFilePermissions.fromString("rw-r--r--"));

        String contentHash = sha256(CONTENT.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var file = new CapabilityMaterialization.File("SKILL.md", CONTENT.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, contentHash);
        var version = new CapabilityMaterialization.Version(capabilityId, versionId, contentHash, List.of(file));
        var manifest = new CapabilityMaterialization.Manifest(spaceId, taskId, List.of(version));
        var command = new CapabilityMaterializationCommand(operationId, operationId, spaceId, taskId, attemptId,
                actorId, ownerId, workspaceId, 1, 1, manifest.hash(), List.of(selection));
        var policy = new SandboxPolicy(sandboxImage, helperImage, workspaceRoot.toString(), mountUuid, quotaDevice, runtimeAttestation);

        try (var runtime = new DockerSandboxRuntime(policy, WorkspaceEffectBoundary.NONE,
                named(operationId, "runner"), new AuxiliaryContainerSlot())) {
            com.agentplatform.domain.capability.CapabilityMaterializationReceipt receipt;
            try {
                receipt = runtime.verifyCapabilityMounts(command, manifest,
                        Map.of(version.mountTarget(), material), CAPABILITY_POLICY, () -> {});
            } catch (CapabilityMaterializationFailure failure) {
                var failed = failure.receipt();
                System.out.println("R62_JAVA_CANARY_DIAGNOSTIC error=" + failed.errorCode()
                        + " exit=" + failed.exitCode() + " stopConfirmed=" + failed.stopConfirmed()
                        + " verifiedFiles=" + failed.verifiedFiles());
                throw failure;
            }
            if (!"COMMITTED".equals(receipt.state()) || !receipt.stopConfirmed() || !receipt.readOnlyMounts()
                    || receipt.verifiedFiles() != 1 || !Integer.valueOf(0).equals(receipt.exitCode())
                    || !operationId.equals(receipt.operationId())) {
                throw new IllegalStateException("R62_JAVA_MATERIALIZATION_RECEIPT_INVALID");
            }
            runtime.removeCapabilityMaterialization(command, receipt);
            Path container = workspaceRoot.resolve(".platform-capability-materials").resolve(operationId.toString());
            removePrivateTree(container);
            Files.delete(work);
            Files.delete(work.getParent());
            Files.delete(work.getParent().getParent());
            if (Files.exists(container, LinkOption.NOFOLLOW_LINKS) || Files.exists(work.getParent().getParent(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("R62_JAVA_MATERIALIZATION_CLEANUP_INCOMPLETE");
            }
            System.out.println("R62_JAVA_CAPABILITY_MOUNT_PASS operation=" + operationId
                    + " manifest=" + manifest.hash() + " image=" + receipt.imageId()
                    + " verifiedFiles=" + receipt.verifiedFiles() + " stopped=" + receipt.stopConfirmed());
        }
    }

    private static UUID named(UUID operation, String part) {
        return UUID.nameUUIDFromBytes((operation + "|" + part).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void removePrivateTree(Path root) throws Exception {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isSymbolicLink(path)) throw new IllegalStateException("R62_CANARY_LINK_UNEXPECTED");
                Files.delete(path);
            }
        }
    }
}
