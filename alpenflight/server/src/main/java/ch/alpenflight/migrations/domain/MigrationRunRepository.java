package ch.alpenflight.migrations.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Domain port for {@link MigrationRun} persistence. No tenant scope:
 * the run row hangs off {@code t_migration_upload} which is pre-tenant.
 */
public interface MigrationRunRepository {

    MigrationRun save(MigrationRun run);

    Optional<MigrationRun> findById(UUID id);

    /** Most recent run for an upload — drives the SPA status poll. */
    Optional<MigrationRun> findLatestByUpload(UUID uploadId);

    void flush();
}
