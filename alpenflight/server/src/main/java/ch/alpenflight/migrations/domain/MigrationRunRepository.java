package ch.alpenflight.migrations.domain;

import java.util.Optional;
import java.util.UUID;

public interface MigrationRunRepository {

    MigrationRun save(MigrationRun run);

    Optional<MigrationRun> findById(UUID id);

    Optional<MigrationRun> findLatestByUpload(UUID uploadId);

    void flush();
}
