package com.agentplatform.app.capability;

import com.agentplatform.app.files.AuthorizedAssetStream;
import com.agentplatform.domain.capability.CapabilityMaterialization;
import com.agentplatform.domain.files.AssetStore;
import com.agentplatform.infrastructure.capability.SkillArchiveValidator;
import com.agentplatform.infrastructure.capability.SkillMaterializationVerifier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Stages one trusted, selected archive. The caller must still enforce mount and stop ownership. */
public final class SkillMaterialStaging {
    private final AssetStore assets;
    private final SkillMaterializationVerifier verifier;
    private final Path temporaryDirectory;

    public SkillMaterialStaging(AssetStore assets, SkillMaterializationVerifier verifier, Path temporaryDirectory) {
        this.assets = Objects.requireNonNull(assets);
        this.verifier = Objects.requireNonNull(verifier);
        this.temporaryDirectory = Objects.requireNonNull(temporaryDirectory);
    }

    /** key and expected version must come from authorized server-side metadata, never client paths. */
    public Staged stage(String key, long bytes, CapabilityMaterialization.Version version, Runnable authorize) throws IOException {
        Objects.requireNonNull(key); Objects.requireNonNull(version); Objects.requireNonNull(authorize);
        if (bytes < 0 || bytes > SkillArchiveValidator.MAX_ARCHIVE) throw new IllegalArgumentException("ARCHIVE_SIZE_OR_TYPE");
        authorize.run();
        Path archive = Files.createTempFile(temporaryDirectory, "skill-material-", ".zip");
        boolean success = false;
        try {
            try (var source = assets.open(key, null, null);
                 var input = new AuthorizedAssetStream(source.stream(), bytes, version.archiveSha256(), authorize);
                 var output = Files.newOutputStream(archive)) {
                if (source.bytes() != bytes || source.totalBytes() != bytes) throw new IOException("ASSET_LENGTH_MISMATCH");
                input.transferTo(output);
            }
            authorize.run();
            verifier.verify(archive, version);
            authorize.run();
            success = true;
            return new Staged(archive);
        } finally {
            if (!success) Files.deleteIfExists(archive);
        }
    }

    public record Staged(Path archive) implements AutoCloseable {
        public Staged { Objects.requireNonNull(archive); }
        @Override public void close() throws IOException { Files.deleteIfExists(archive); }
    }
}
