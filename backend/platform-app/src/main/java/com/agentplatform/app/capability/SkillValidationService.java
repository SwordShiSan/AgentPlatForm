package com.agentplatform.app.capability;

import com.agentplatform.app.files.AuthorizedAssetStream;
import com.agentplatform.domain.capability.SkillValidationRepository;
import com.agentplatform.domain.capability.SkillValidationRepository.Command;
import com.agentplatform.domain.files.AssetStore;
import com.agentplatform.infrastructure.capability.SkillArchiveValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Only validates a verified archive; never executes package content or installs dependencies. */
public final class SkillValidationService {
    private final SkillValidationRepository repository;
    private final AssetStore assets;
    private final SkillArchiveValidator validator;
    private final Path temporaryDirectory;

    public SkillValidationService(SkillValidationRepository repository, AssetStore assets,
                                  SkillArchiveValidator validator, Path temporaryDirectory) {
        this.repository = Objects.requireNonNull(repository); this.assets = Objects.requireNonNull(assets);
        this.validator = Objects.requireNonNull(validator); this.temporaryDirectory = Objects.requireNonNull(temporaryDirectory);
    }

    public void validate(Command command) {
        validate(command, () -> true);
    }

    public void validate(Command command, java.util.function.BooleanSupplier active) {
        Path archive = null;
        try {
            Runnable guard = () -> {
                if (!active.getAsBoolean()) throw new com.agentplatform.domain.files.FileFailure(409, "SKILL_IMPORT_STALE_OWNER");
                repository.guard(command);
            };
            guard.run();
            if (command.bytes() < 0 || command.bytes() > SkillImportService.MAX_ARCHIVE_BYTES)
                throw new IllegalArgumentException("ARCHIVE_SIZE_OR_TYPE");
            archive = Files.createTempFile(temporaryDirectory, "skill-import-", ".zip");
            try (var source = assets.open(command.objectKey(), null, null);
                 var input = new AuthorizedAssetStream(source.stream(), command.bytes(), command.sha256(), guard);
                 var output = Files.newOutputStream(archive)) {
                if (source.bytes() != command.bytes() || source.totalBytes() != command.bytes())
                    throw new IOException("ASSET_LENGTH_MISMATCH");
                input.transferTo(output);
            }
            // Close the object transfer before validation and any later object operation.
            final SkillArchiveValidator.Manifest result;
            try { result = validator.validate(archive); }
            catch (IllegalArgumentException invalidArchive) {
                repository.rejected(command, "SKILL_ARCHIVE_INVALID");
                return;
            } catch (IOException invalidArchive) {
                Throwable cause = invalidArchive;
                while (cause != null && !(cause instanceof java.util.zip.ZipException)) cause = cause.getCause();
                if (cause == null) throw invalidArchive;
                repository.rejected(command, "SKILL_ARCHIVE_INVALID"); return;
            }
            if (!result.archiveSha256().equals(command.sha256())) throw new IOException("ASSET_HASH_MISMATCH");
            guard.run();
            repository.draft(command, new SkillValidationRepository.Manifest(result.archiveSha256(), result.name(),
                    result.description(), result.expandedBytes(), result.files().stream()
                    .map(f -> new SkillValidationRepository.Resource(f.path(), f.bytes(), f.sha256())).toList(), result.dependenciesJson()));
        } catch (IOException | RuntimeException uncertain) {
            try { repository.unknown(command, "SKILL_IMPORT_RESULT_UNKNOWN"); }
            catch (RuntimeException unavailable) { uncertain.addSuppressed(unavailable); }
        } finally {
            if (archive != null) try { Files.deleteIfExists(archive); }
            catch (IOException failed) { org.slf4j.LoggerFactory.getLogger(SkillValidationService.class)
                    .warn("Skill validation temporary file cleanup failed for import {}", command.importId()); }
        }
    }
}
