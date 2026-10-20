package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.ChatData.Actor;
import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.domain.chat.ChatRepository;
import com.agentplatform.infrastructure.chat.CredentialCipher;
import com.agentplatform.types.Uuid7;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Trusted offline operator, never registered in the API or worker profiles. */
@Component
@Profile("chat-ops")
public final class ChatOperator implements ApplicationRunner {
    private record RestoreEvidence(UUID commandId,UUID taskId,UUID checkpointId,UUID stopAttemptId,
            Long expectedTaskVersion,Long expectedRevision,String operator) {}
    private record ReconciliationEvidence(UUID callId,UUID commandId,Long expectedRevision,
            com.agentplatform.domain.chat.CancellationLedger.Decision decision,UUID evidenceReference,
            String evidenceKind,String operator,String providerRequestId,Long inputTokens,Long outputTokens) {}
    private final ChatRepository repository;
    private final CredentialCipher cipher;

    public ChatOperator(ChatRepository repository, CredentialCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    private static String option(ApplicationArguments args, String name) {
        var values = args.getOptionValues(name);
        if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
            throw new ChatFailure(400, "INVALID_OPERATOR_OPTION");
        }
        return values.getFirst();
    }

    @Override public void run(ApplicationArguments args) throws Exception {
        if (args.containsOption("key") || args.containsOption("api-key") || args.containsOption("password")) {
            throw new ChatFailure(400, "SECRET_ARGUMENT_FORBIDDEN");
        }
        String action = option(args, "chat-action");
        switch (action) {
            case "verify-restored-credentials" -> {
                int count=0;
                for(var credential:repository.recoveryCredentials()){
                    if(cipher.decrypt(credential).isBlank())throw new ChatFailure(409,"EMPTY_RESTORED_CREDENTIAL");
                    count++;
                }
                if(count==0)throw new ChatFailure(409,"RESTORED_CREDENTIAL_MISSING");
                System.out.println("Restored credential authentication/decryption PASS; count="+count+"; no provider request made.");
            }
            case "import-credential" -> {
                String scope = option(args, "scope");
                if (!Set.of("PLATFORM", "USER").contains(scope)) throw new ChatFailure(400, "INVALID_CREDENTIAL_SCOPE");
                UUID space = "USER".equals(scope) ? UUID.fromString(option(args, "space-id")) : null;
                UUID user = "USER".equals(scope) ? UUID.fromString(option(args, "user-id")) : null;
                if ("PLATFORM".equals(scope) && (args.containsOption("space-id") || args.containsOption("user-id"))) {
                    throw new ChatFailure(400, "INVALID_CREDENTIAL_SCOPE");
                }
                byte[] input = privateFile(Path.of(option(args, "key-file")));
                try {
                    String value = new String(input, StandardCharsets.UTF_8).strip();
                    if (value.isBlank() || value.length() > 4096 || value.codePoints().anyMatch(Character::isWhitespace)) {
                        throw new ChatFailure(400, "INVALID_CREDENTIAL_INPUT");
                    }
                    UUID id = repository.importCredential(cipher.encrypt(Uuid7.next(), scope, space, user, value));
                    System.out.println("Credential imported: " + id + " scope=" + scope);
                } finally { Arrays.fill(input, (byte) 0); }
            }
            case "bind-model" -> {
                repository.bind(UUID.fromString(option(args, "model-id")),
                        new Actor(UUID.fromString(option(args, "user-id")), UUID.fromString(option(args, "space-id"))),
                        UUID.fromString(option(args, "credential-id")), option(args, "source-policy"));
                System.out.println("Model credential binding updated.");
            }
            case "revoke-credential" -> {
                repository.revokeCredential(UUID.fromString(option(args, "credential-id")));
                System.out.println("Credential revoked; no fallback credential selected.");
            }
            case "inspect-reconciliation" -> {
                var projection=new ReconciliationService(repository).inspect(UUID.fromString(option(args,"task-id")));
                System.out.println(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(projection));
            }
            case "inspect-recovery" -> {
                var projection=new CheckpointRestoreService(repository).inspect(UUID.fromString(option(args,"task-id")));
                System.out.println(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(projection));
            }
            case "recover-checkpoint" -> {
                byte[] bytes=privateFile(Path.of(option(args,"evidence-file")));
                try {
                    RestoreEvidence evidence;
                    try { evidence=tools.jackson.databind.json.JsonMapper.builder()
                            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build().readValue(bytes,RestoreEvidence.class); }
                    catch(RuntimeException invalid){throw new ChatFailure(422,"RECOVERY_COMMAND_INVALID");}
                    if(evidence.expectedTaskVersion()==null||evidence.expectedRevision()==null||evidence.expectedTaskVersion()<1||evidence.expectedRevision()<0)
                        throw new ChatFailure(422,"RECOVERY_COMMAND_INVALID");
                    var result=new CheckpointRestoreService(repository).restore(new com.agentplatform.domain.chat.RecoveryLedger.RestoreCommand(
                            evidence.commandId(),evidence.taskId(),evidence.checkpointId(),evidence.stopAttemptId(),evidence.expectedTaskVersion(),evidence.expectedRevision(),evidence.operator()));
                    System.out.println("Recovery command recorded; task="+result.taskId()+" revision="+result.revision()+"; dispatch uses the existing outbox/MQ path.");
                } finally {Arrays.fill(bytes,(byte)0);}
            }
            case "reconcile-call" -> {
                byte[] bytes=privateFile(Path.of(option(args,"evidence-file")));
                try {
                    ReconciliationEvidence evidence;
                    try { evidence=tools.jackson.databind.json.JsonMapper.builder()
                            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()
                            .readValue(bytes,ReconciliationEvidence.class); }
                    catch(RuntimeException invalid){throw new ChatFailure(422,"RECONCILIATION_EVIDENCE_INVALID");}
                    if(evidence.expectedRevision()==null)throw new ChatFailure(422,"RECONCILIATION_EVIDENCE_INVALID");
                    String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
                    new ReconciliationService(repository).resolve(new com.agentplatform.domain.chat.CancellationLedger.Command(
                            evidence.callId(),evidence.commandId(),evidence.expectedRevision(),evidence.decision(),evidence.evidenceReference(),
                            digest,evidence.evidenceKind(),evidence.operator(),evidence.providerRequestId(),evidence.inputTokens(),evidence.outputTokens()));
                    System.out.println("Call reconciliation recorded; no model request was dispatched.");
                } finally {Arrays.fill(bytes,(byte)0);}
            }
            case "reconcile-stopped-owner" -> {
                if (!args.containsOption("proof-file") || args.containsOption("stopped-owner-confirmed")) throw new ChatFailure(409, "STOPPED_OWNER_PROOF_REQUIRED");
                byte[] key=privateFile(Path.of(option(args,"proof-key-file")));
                try{repository.recoverStopped(com.agentplatform.infrastructure.chat.StoppedOwnerProof.verify(privateFile(Path.of(option(args,"proof-file"))),key));}
                finally{Arrays.fill(key,(byte)0);}
                System.out.println("Stopped owner reconciled; unknown calls remain reserved and are not replayed.");
            }
            default -> throw new ChatFailure(400, "INVALID_OPERATOR_ACTION");
        }
    }

    private static byte[] privateFile(Path path) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 8192) {
            throw new ChatFailure(400, "INVALID_PRIVATE_FILE");
        }
        // Run in the Linux operator container; host-side stdin helpers create a 0600 tmpfs file.
        var permissions = Files.getPosixFilePermissions(path);
        if (permissions.stream().anyMatch(p -> Set.of(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE).contains(p))) {
            throw new ChatFailure(400, "PRIVATE_FILE_PERMISSIONS_REQUIRED");
        }
        return Files.readAllBytes(path);
    }
}
