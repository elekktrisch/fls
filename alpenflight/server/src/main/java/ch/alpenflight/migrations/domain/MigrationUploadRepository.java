package ch.alpenflight.migrations.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MigrationUploadRepository {

    Optional<MigrationUpload> findAwaitingByUser(UUID userId);

    Optional<MigrationUpload> findById(UUID id);

    List<MigrationUpload> findExpired(Instant now);

    MigrationUpload save(MigrationUpload row);

    void flush();
}
