package com.agentplatform.infrastructure.capability;

import com.agentplatform.domain.capability.CapabilityMaterialization;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Verifies selected immutable material against the archive, without executing or extracting it. */
public final class SkillMaterializationVerifier {
    private final SkillArchiveValidator validator;

    public SkillMaterializationVerifier(SkillArchiveValidator validator) {
        this.validator = Objects.requireNonNull(validator);
    }

    public SkillArchiveValidator.Manifest verify(Path archive, CapabilityMaterialization.Version expected) throws IOException {
        Objects.requireNonNull(expected, "expected");
        var actual = validator.validate(archive);
        List<CapabilityMaterialization.File> files = actual.files().stream()
                .map(file -> new CapabilityMaterialization.File(file.path(), file.bytes(), file.sha256()))
                .toList();
        if (!actual.archiveSha256().equals(expected.archiveSha256()) || !files.equals(expected.files()))
            throw new IllegalArgumentException("CAPABILITY_MATERIAL_MISMATCH");
        return actual;
    }
}
