package com.agentplatform.domain.chat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Immutable resolved task choices and the identities that govern whether they can be used. */
public record ResolvedTaskConfiguration(UUID workspaceId, ConfigurationResolver.Resolved values,
                                        long modelVersion, UUID credentialId, long credentialRevision,
                                        String pricingVersion, long inputPrice, long outputPrice, String policyVersion) {
    public static final String RESOLVER_VERSION = "r61-v1";
    public ResolvedTaskConfiguration {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(pricingVersion, "pricingVersion");
        if (modelVersion < 0 || credentialRevision < 0 || inputPrice < 0 || outputPrice < 0 || policyVersion.length() > 512 || pricingVersion.length() > 512)
            throw new IllegalArgumentException("Invalid configuration identity");
        if (credentialId == null && credentialRevision != 0)
            throw new IllegalArgumentException("Credential revision requires an identity");
    }

    /** Excludes unrelated preference revisions such as layout and recently viewed files. */
    public String effectiveHash() {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var data = new DataOutputStream(bytes)) {
                data.writeUTF(RESOLVER_VERSION);
                data.writeUTF(workspaceId == null ? "" : workspaceId.toString());
                put(data, values.modelId().value().toString(), values.modelId().source());
                put(data, values.credentialSource().value().name(), values.credentialSource().source());
                put(data, values.planMode().value().toString(), values.planMode().source());
                put(data, values.permissionMode().value().name(), values.permissionMode().source());
                for (var field : ConfigurationResolver.Field.values()) data.writeBoolean(values.lockedFields().contains(field));
                data.writeLong(modelVersion);
                data.writeUTF(credentialId == null ? "" : credentialId.toString());
                data.writeLong(credentialRevision);
                data.writeUTF(pricingVersion);
                data.writeLong(inputPrice);
                data.writeLong(outputPrice);
                data.writeUTF(policyVersion);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Could not hash configuration", impossible);
        }
    }
    private static void put(DataOutputStream data, String value, ConfigurationResolver.Source source) throws IOException {
        data.writeUTF(value);
        data.writeUTF(source.name());
    }
}
