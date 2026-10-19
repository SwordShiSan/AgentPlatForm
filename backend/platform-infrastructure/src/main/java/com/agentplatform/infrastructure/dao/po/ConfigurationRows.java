package com.agentplatform.infrastructure.dao.po;
import java.util.UUID;
import java.time.Instant;
public final class ConfigurationRows {
    private ConfigurationRows() {}
    public record UserModelCredentialRow7(Long value2, UUID value1) {}
    public record UserModelCredentialRow9(Long value1, String value2) {}
    public record ModelDefinitionRow12(UUID value1) {}
    public record UserPreferenceRow14(String value1, String value2) {}
    public record UserPreferenceRevisionRow15(Long value1) {}
    public record CredentialRow19(String value1, UUID value2, UUID value3) {}
    public record CredentialRow21(UUID id, String scopeType, UUID spaceId, UUID ownerUserId, byte[] ciphertext, byte[] nonce, Integer keyVersion, Long revision) {}
    public record CredentialRow32(String scopeType, UUID spaceId, UUID ownerUserId, byte[] ciphertext, byte[] nonce, Integer keyVersion, Long revision) {}
    public record RuntimeBundleRow54(String value1) {}
    public record UserModelCredentialRow55(UUID id, Long revision, String modelName, String pricingVersion, Long modelVersion, Long inputMicrosPerMillion, Long outputMicrosPerMillion) {}
    public record ModelBindingRow60(String value1) {}
    public record ModelDefinitionRow67(UUID value1, String value2, Long value3) {}
}
